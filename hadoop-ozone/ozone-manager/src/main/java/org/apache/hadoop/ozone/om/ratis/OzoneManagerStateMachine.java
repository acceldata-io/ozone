/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.om.ratis;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ServiceException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.ozone.common.ha.ratis.RatisSnapshotInfo;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.OzoneManagerPrepareState;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OMRatisHelper;
import org.apache.hadoop.ozone.om.ratis.metrics.OzoneManagerStateMachineMetrics;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerRatisUtils;
import org.apache.hadoop.ozone.om.lock.OMLockDetails;
import org.apache.hadoop.ozone.om.response.DummyOMClientResponse;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.protocolPB.OzoneManagerRequestHandler;
import org.apache.hadoop.ozone.protocolPB.RequestHandler;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.proto.RaftProtos.StateMachineLogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.StateMachineException;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.ExitUtils;
import org.apache.ratis.util.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status.INTERNAL_ERROR;
import static org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status.METADATA_ERROR;
import static org.apache.hadoop.ozone.OzoneConsts.TRANSACTION_INFO_KEY;

/**
 * The OM StateMachine is the state machine for OM Ratis server. It is
 * responsible for applying ratis committed transactions to
 * {@link OzoneManager}.
 */
public class OzoneManagerStateMachine extends BaseStateMachine {

  public static final Logger LOG =
      LoggerFactory.getLogger(OzoneManagerStateMachine.class);
  private final SimpleStateMachineStorage storage =
      new SimpleStateMachineStorage();
  private final OzoneManagerRatisServer omRatisServer;
  private final OzoneManager ozoneManager;
  private RequestHandler handler;
  private RaftGroupId raftGroupId;
  private OzoneManagerDoubleBuffer ozoneManagerDoubleBuffer;
  private final RatisSnapshotInfo snapshotInfo;
  private final ExecutorService executorService;
  private final ExecutorService installSnapshotExecutor;
  private final boolean isTracingEnabled;
  private final AtomicInteger statePausedCount = new AtomicInteger(0);
  private final String threadPrefix;

  // Map which contains index and term for the ratis transactions which are
  // stateMachine entries which are received through applyTransaction.
  private ConcurrentMap<Long, Long> applyTransactionMap =
      new ConcurrentSkipListMap<>();

  // Map which contains index and term for the ratis transactions which are
  // conf/metadata entries which are received through notifyIndexUpdate.
  private ConcurrentMap<Long, Long> ratisTransactionMap =
      new ConcurrentSkipListMap<>();
  private OzoneManagerStateMachineMetrics metrics;


  public OzoneManagerStateMachine(OzoneManagerRatisServer ratisServer,
      boolean isTracingEnabled) throws IOException {
    this.omRatisServer = ratisServer;
    this.isTracingEnabled = isTracingEnabled;
    this.ozoneManager = omRatisServer.getOzoneManager();

    this.snapshotInfo = ozoneManager.getSnapshotInfo();
    loadSnapshotInfoFromDB();
    this.threadPrefix = ozoneManager.getThreadNamePrefix();

    this.ozoneManagerDoubleBuffer = buildDoubleBufferForRatis();

    this.handler = new OzoneManagerRequestHandler(ozoneManager,
        ozoneManagerDoubleBuffer);

    ThreadFactory build = new ThreadFactoryBuilder().setDaemon(true)
        .setNameFormat(threadPrefix +
            "OMStateMachineApplyTransactionThread - %d").build();
    this.executorService = HadoopExecutors.newSingleThreadExecutor(build);

    ThreadFactory installSnapshotThreadFactory = new ThreadFactoryBuilder()
        .setNameFormat(threadPrefix + "InstallSnapshotThread").build();
    this.installSnapshotExecutor =
        HadoopExecutors.newSingleThreadExecutor(installSnapshotThreadFactory);
    this.metrics = OzoneManagerStateMachineMetrics.create();
  }

  /**
   * Initializes the State Machine with the given server, group and storage.
   */
  @Override
  public void initialize(RaftServer server, RaftGroupId id,
      RaftStorage raftStorage) throws IOException {
    getLifeCycle().startAndTransition(() -> {
      super.initialize(server, id, raftStorage);
      this.raftGroupId = id;
      storage.init(raftStorage);
    });
  }

  @Override
  public synchronized void reinitialize() throws IOException {
    loadSnapshotInfoFromDB();
    if (getLifeCycleState() == LifeCycle.State.PAUSED) {
      unpause(getLastAppliedTermIndex().getIndex(),
          getLastAppliedTermIndex().getTerm());
    }
  }

  @Override
  public SnapshotInfo getLatestSnapshot() {
    LOG.debug("Latest Snapshot Info {}", snapshotInfo);
    return snapshotInfo;
  }

  @Override
  public void notifyLeaderChanged(RaftGroupMemberId groupMemberId,
                                  RaftPeerId newLeaderId) {
    // Initialize OMHAMetrics
    ozoneManager.omHAMetricsInit(newLeaderId.toString());
  }

  /**
   * Called to notify state machine about indexes which are processed
   * internally by Raft Server, this currently happens when conf entries are
   * processed in raft Server. This keep state machine to keep a track of index
   * updates.
   * @param currentTerm term of the current log entry
   * @param index index which is being updated
   */
  @Override
  public void notifyTermIndexUpdated(long currentTerm, long index) {
    // SnapshotInfo should be updated when the term changes.
    // The index here refers to the log entry index and the index in
    // SnapshotInfo represents the snapshotIndex i.e. the index of the last
    // transaction included in the snapshot. Hence, snaphsotInfo#index is not
    // updated here.

    // We need to call updateLastApplied here because now in ratis when a
    // node becomes leader, it is checking stateMachineIndex >=
    // placeHolderIndex (when a node becomes leader, it writes a conf entry
    // with some information like its peers and termIndex). So, calling
    // updateLastApplied updates lastAppliedTermIndex.
    computeAndUpdateLastAppliedIndex(index, currentTerm, null, false);
  }

  /**
   * Called to notify state machine about configuration changes.
   * Configurations changes include addition of newly bootstrapped OM.
   */
  @Override
  public void notifyConfigurationChanged(long term, long index,
      RaftProtos.RaftConfigurationProto newRaftConfiguration) {
    List<RaftProtos.RaftPeerProto> newPeers =
        newRaftConfiguration.getPeersList();
    LOG.info("Received Configuration change notification from Ratis. New Peer" +
        " list:\n{}", newPeers);

    List<String> newPeerIds = new ArrayList<>();
    for (RaftProtos.RaftPeerProto raftPeerProto : newPeers) {
      newPeerIds.add(RaftPeerId.valueOf(raftPeerProto.getId()).toString());
    }
    // Check and update the peer list in OzoneManager
    ozoneManager.updatePeerList(newPeerIds);
  }

  /**
   * Called to notify state machine about the snapshot install result.
   * Trigger the cleanup of candidate DB dir.
   * @param result InstallSnapshotResult
   * @param snapshotIndex the index of installed snapshot
   * @param peer the peer which fini
   */
  @Override
  public void notifySnapshotInstalled(RaftProtos.InstallSnapshotResult result,
                                      long snapshotIndex, RaftPeer peer) {
    LOG.info("Receive notifySnapshotInstalled event {} for the peer: {}" +
        " snapshotIndex: {}.", result, peer.getId(), snapshotIndex);
    switch (result) {
    case SUCCESS:
    case SNAPSHOT_UNAVAILABLE:
      // Currently, only trigger for the one who installed snapshot
      if (ozoneManager.getOmRatisServer().getServer().getPeer().equals(peer)) {
        ozoneManager.getOmSnapshotProvider().init();
      }
      break;
    default:
      break;
    }
  }

  /**
   * Validate/pre-process the incoming update request in the state machine.
   * @return the content to be written to the log entry. Null means the request
   * should be rejected.
   * @throws IOException thrown by the state machine while validating
   */
  @Override
  public TransactionContext startTransaction(
      RaftClientRequest raftClientRequest) throws IOException {
    ByteString messageContent = raftClientRequest.getMessage().getContent();
    OMRequest omRequest = OMRatisHelper.convertByteStringToOMRequest(
        messageContent);

    Preconditions.checkArgument(raftClientRequest.getRaftGroupId().equals(
        raftGroupId));
    try {
      handler.validateRequest(omRequest);
    } catch (IOException ioe) {
      TransactionContext ctxt = TransactionContext.newBuilder()
          .setClientRequest(raftClientRequest)
          .setStateMachine(this)
          .setServerRole(RaftProtos.RaftPeerRole.LEADER)
          .build();
      ctxt.setException(ioe);
      return ctxt;
    }
    return handleStartTransactionRequests(raftClientRequest, omRequest);
  }

  @Override
  public TransactionContext preAppendTransaction(TransactionContext trx)
      throws IOException {
    final OMRequest request = (OMRequest) trx.getStateMachineContext();
    OzoneManagerProtocolProtos.Type cmdType = request.getCmdType();

    OzoneManagerPrepareState prepareState = ozoneManager.getPrepareState();

    if (cmdType == OzoneManagerProtocolProtos.Type.Prepare) {
      // Must authenticate prepare requests here, since we must determine
      // whether or not to apply the prepare gate before proceeding with the
      // prepare request.
      UserGroupInformation userGroupInformation =
          UserGroupInformation.createRemoteUser(
          request.getUserInfo().getUserName());
      if (ozoneManager.getAclsEnabled()
          && !ozoneManager.isAdmin(userGroupInformation)) {
        String message = "Access denied for user " + userGroupInformation
            + ". "
            + "Superuser privilege is required to prepare ozone managers.";
        OMException cause =
            new OMException(message, OMException.ResultCodes.ACCESS_DENIED);
        // Leader should not step down because of this failure.
        throw new StateMachineException(message, cause, false);
      } else {
        prepareState.enablePrepareGate();
      }
    }

    // In prepare mode, only prepare and cancel requests are allowed to go
    // through.
    if (prepareState.requestAllowed(cmdType)) {
      return trx;
    } else {
      String message = "Cannot apply write request " +
          request.getCmdType().name() + " when OM is in prepare mode.";
      OMException cause = new OMException(message,
          OMException.ResultCodes.NOT_SUPPORTED_OPERATION_WHEN_PREPARED);
      // Indicate that the leader should not step down because of this failure.
      throw new StateMachineException(message, cause, false);
    }
  }

  /*
   * Apply a committed log entry to the state machine.
   */
  @Override
  public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    try {
      // For the Leader, the OMRequest is set in trx in startTransaction.
      // For Followers, the OMRequest hast to be converted from the log entry.
      final Object context = trx.getStateMachineContext();
      final OMRequest request = context != null ? (OMRequest) context
          : OMRatisHelper.convertByteStringToOMRequest(
          trx.getStateMachineLogEntry().getLogData());
      long trxLogIndex = trx.getLogEntry().getIndex();
      // In the current approach we have one single global thread executor.
      // with single thread. Right now this is being done for correctness, as
      // applyTransaction will be run on multiple OM's we want to execute the
      // transactions in the same order on all OM's, otherwise there is a
      // chance that OM replica's can be out of sync.
      // TODO: In this way we are making all applyTransactions in
      // OM serial order. Revisit this in future to use multiple executors for
      // volume/bucket.

      // Reason for not immediately implementing executor per volume is, if
      // one executor operations are slow, we cannot update the
      // lastAppliedIndex in OzoneManager StateMachine, even if other
      // executor has completed the transactions with id more.

      // We have 300 transactions, And for each volume we have transactions
      // of 150. Volume1 transactions 0 - 149 and Volume2 transactions 150 -
      // 299.
      // Example: Executor1 - Volume1 - 100 (current completed transaction)
      // Example: Executor2 - Volume2 - 299 (current completed transaction)

      // Now we have applied transactions of 0 - 100 and 149 - 299. We
      // cannot update lastAppliedIndex to 299. We need to update it to 100,
      // since 101 - 149 are not applied. When OM restarts it will
      // applyTransactions from lastAppliedIndex.
      // We can update the lastAppliedIndex to 100, and update it to 299,
      // only after completing 101 - 149. In initial stage, we are starting
      // with single global executor. Will revisit this when needed.

      // Add the term index and transaction log index to applyTransaction map
      // . This map will be used to update lastAppliedIndex.

      CompletableFuture<Message> ratisFuture =
          new CompletableFuture<>();
      applyTransactionMap.put(trxLogIndex, trx.getLogEntry().getTerm());

      //if there are too many pending requests, wait for doubleBuffer flushing
      ozoneManagerDoubleBuffer.acquireUnFlushedTransactions(1);

      CompletableFuture<OMResponse> future = CompletableFuture.supplyAsync(
          () -> runCommand(request, trxLogIndex), executorService);
      future.thenApply(omResponse -> {
        if (!omResponse.getSuccess()) {
          // When INTERNAL_ERROR or METADATA_ERROR it is considered as
          // critical error and terminate the OM. Considering INTERNAL_ERROR
          // also for now because INTERNAL_ERROR is thrown for any error
          // which is not type OMException.

          // Not done future with completeExceptionally because if we do
          // that OM will still continue applying transaction until next
          // snapshot. So in OM case if a transaction failed with un
          // recoverable error and if we wait till snapshot to terminate
          // OM, then if some client requested the read transaction of the
          // failed request, there is a chance we shall give wrong result.
          // So, to avoid these kind of issue, we should terminate OM here.
          if (omResponse.getStatus() == INTERNAL_ERROR) {
            terminate(omResponse, OMException.ResultCodes.INTERNAL_ERROR);
          } else if (omResponse.getStatus() == METADATA_ERROR) {
            terminate(omResponse, OMException.ResultCodes.METADATA_ERROR);
          }
        }

        // For successful response and for all other errors which are not
        // critical, we can complete future normally.
        ratisFuture.complete(OMRatisHelper.convertResponseToMessage(
            omResponse));
        return ratisFuture;
      });
      return ratisFuture;
    } catch (Exception e) {
      return completeExceptionally(e);
    }
  }

  /**
   * Terminate OM.
   * @param omResponse
   * @param resultCode
   */
  private void terminate(OMResponse omResponse,
      OMException.ResultCodes resultCode) {
    OMException exception = new OMException(omResponse.getMessage(),
        resultCode);
    String errorMessage = "OM Ratis Server has received unrecoverable " +
        "error, to avoid further DB corruption, terminating OM. Error " +
        "Response received is:" + omResponse;
    ExitUtils.terminate(1, errorMessage, exception, LOG);
  }

  /**
   * Query the state machine. The request must be read-only.
   */
  @Override
  public CompletableFuture<Message> query(Message request) {
    try {
      OMRequest omRequest = OMRatisHelper.convertByteStringToOMRequest(
          request.getContent());
      return CompletableFuture.completedFuture(queryCommand(omRequest));
    } catch (IOException e) {
      return completeExceptionally(e);
    }
  }

  @Override
  public synchronized void pause() {
    LOG.info("OzoneManagerStateMachine is pausing");
    statePausedCount.incrementAndGet();
    if (getLifeCycleState() == LifeCycle.State.PAUSED) {
      return;
    }
    final LifeCycle lc = getLifeCycle();
    if (lc.getCurrentState() != LifeCycle.State.NEW) {
      getLifeCycle().transition(LifeCycle.State.PAUSING);
      getLifeCycle().transition(LifeCycle.State.PAUSED);
    }

    ozoneManagerDoubleBuffer.stop();
  }

  /**
   * Unpause the StateMachine, re-initialize the DoubleBuffer and update the
   * lastAppliedIndex. This should be done after uploading new state to the
   * StateMachine.
   */
  public synchronized void unpause(long newLastAppliedSnaphsotIndex,
      long newLastAppliedSnapShotTermIndex) {
    LOG.info("OzoneManagerStateMachine is un-pausing");
    if (statePausedCount.decrementAndGet() == 0) {
      getLifeCycle().startAndTransition(() -> {
        this.ozoneManagerDoubleBuffer = buildDoubleBufferForRatis();
        handler.updateDoubleBuffer(ozoneManagerDoubleBuffer);
        this.setLastAppliedTermIndex(TermIndex.valueOf(
            newLastAppliedSnapShotTermIndex, newLastAppliedSnaphsotIndex));
      });
    }
  }

  public OzoneManagerDoubleBuffer buildDoubleBufferForRatis() {
    int maxUnflushedTransactionSize = ozoneManager.getConfiguration()
        .getInt(OMConfigKeys.OZONE_OM_UNFLUSHED_TRANSACTION_MAX_COUNT,
            OMConfigKeys.OZONE_OM_UNFLUSHED_TRANSACTION_MAX_COUNT_DEFAULT);
    return new OzoneManagerDoubleBuffer.Builder()
        .setOmMetadataManager(ozoneManager.getMetadataManager())
        .setOzoneManagerRatisSnapShot(this::updateLastAppliedIndex)
        .setmaxUnFlushedTransactionCount(maxUnflushedTransactionSize)
        .setIndexToTerm(this::getTermForIndex).setThreadPrefix(threadPrefix)
        .setS3SecretManager(ozoneManager.getS3SecretManager())
        .enableRatis(true)
        .enableTracing(isTracingEnabled)
        .build();
  }

  /**
   * Take OM Ratis snapshot is a dummy operation as when double buffer
   * flushes the lastAppliedIndex is flushed to DB and that is used as
   * snapshot index.
   *
   * @return the last applied index on the state machine which has been
   * stored in the snapshot file.
   */
  @Override
  public long takeSnapshot() throws IOException {
    LOG.info("Current Snapshot Index {}", getLastAppliedTermIndex());
    TermIndex lastTermIndex = getLastAppliedTermIndex();
    long lastAppliedIndex = lastTermIndex.getIndex();
    snapshotInfo.updateTermIndex(lastTermIndex.getTerm(),
        lastAppliedIndex);
    TransactionInfo build = new TransactionInfo.Builder()
        .setTransactionIndex(lastAppliedIndex)
        .setCurrentTerm(lastTermIndex.getTerm()).build();
    Table<String, TransactionInfo> txnInfoTable =
        ozoneManager.getMetadataManager().getTransactionInfoTable();
    txnInfoTable.put(TRANSACTION_INFO_KEY, build);
    ozoneManager.getMetadataManager().getStore().flushDB();
    return lastAppliedIndex;
  }

  /**
   * Leader OM has purged entries from its log. To catch up, OM must download
   * the latest checkpoint from the leader OM and install it.
   * @param roleInfoProto the leader node information
   * @param firstTermIndexInLog TermIndex of the first append entry available
   *                           in the Leader's log.
   * @return the last term index included in the installed snapshot.
   */
  @Override
  public CompletableFuture<TermIndex> notifyInstallSnapshotFromLeader(
      RaftProtos.RoleInfoProto roleInfoProto, TermIndex firstTermIndexInLog) {

    String leaderNodeId = RaftPeerId.valueOf(roleInfoProto.getFollowerInfo()
        .getLeaderInfo().getId().getId()).toString();
    LOG.info("Received install snapshot notification from OM leader: {} with " +
            "term index: {}", leaderNodeId, firstTermIndexInLog);

    CompletableFuture<TermIndex> future = CompletableFuture.supplyAsync(
        () -> ozoneManager.installSnapshotFromLeader(leaderNodeId),
        installSnapshotExecutor);
    return future;
  }

  /**
   * Notifies the state machine that the raft peer is no longer leader.
   */
  @Override
  public void notifyNotLeader(Collection<TransactionContext> pendingEntries)
      throws IOException {
  }

  @Override
  public String toStateMachineLogEntryString(StateMachineLogEntryProto proto) {
    return OMRatisHelper.smProtoToString(proto);
  }

  @Override
  public void close() throws IOException {
    // OM should be shutdown as the StateMachine has shutdown.
    LOG.info("StateMachine has shutdown. Shutdown OzoneManager if not " +
        "already shutdown.");
    if (!ozoneManager.isStopped()) {
      ozoneManager.shutDown("OM state machine is shutdown by Ratis server");
    } else {
      stop();
    }
  }

  /**
   * Handle the RaftClientRequest and return TransactionContext object.
   * @param raftClientRequest
   * @param omRequest
   * @return TransactionContext
   */
  private TransactionContext handleStartTransactionRequests(
      RaftClientRequest raftClientRequest, OMRequest omRequest) {

    return TransactionContext.newBuilder()
        .setClientRequest(raftClientRequest)
        .setStateMachine(this)
        .setServerRole(RaftProtos.RaftPeerRole.LEADER)
        .setLogData(raftClientRequest.getMessage().getContent())
        .setStateMachineContext(omRequest)
        .build();
  }

  /**
   * Submits write request to OM and returns the response Message.
   * @param request OMRequest
   * @return response from OM
   * @throws ServiceException
   */
  private OMResponse runCommand(OMRequest request, long trxLogIndex) {
    try {
      OMClientResponse omClientResponse =
          handler.handleWriteRequest(request, trxLogIndex);
      OMLockDetails omLockDetails = omClientResponse.getOmLockDetails();
      OMResponse omResponse = omClientResponse.getOMResponse();
      if (omLockDetails != null) {
        return omResponse.toBuilder()
            .setOmLockDetails(omLockDetails.toProtobufBuilder()).build();
      } else {
        return omResponse;
      }
    } catch (IOException e) {
      LOG.warn("Failed to write, Exception occurred ", e);
      return createErrorResponse(request, e, trxLogIndex);
    } catch (Throwable e) {
      // For any Runtime exceptions, terminate OM.
      String errorMessage = "Request " + request + " failed with exception";
      ExitUtils.terminate(1, errorMessage, e, LOG);
    }
    return null;
  }

  private OMResponse createErrorResponse(
      OMRequest omRequest, IOException exception, long trxIndex) {
    OMResponse.Builder omResponseBuilder = OMResponse.newBuilder()
        .setStatus(OzoneManagerRatisUtils.exceptionToResponseStatus(exception))
        .setCmdType(omRequest.getCmdType())
        .setTraceID(omRequest.getTraceID())
        .setSuccess(false);
    if (exception.getMessage() != null) {
      omResponseBuilder.setMessage(exception.getMessage());
    }
    OMResponse omResponse = omResponseBuilder.build();
    OMClientResponse omClientResponse = new DummyOMClientResponse(omResponse);
    omClientResponse.setFlushFuture(
        ozoneManagerDoubleBuffer.add(omClientResponse, trxIndex));
    return omResponse;
  }

  /**
   * Update lastAppliedIndex term and it's corresponding term in the
   * stateMachine.
   * @param flushedEpochs
   */
  public void updateLastAppliedIndex(List<Long> flushedEpochs) {
    Preconditions.checkArgument(flushedEpochs.size() > 0);
    computeAndUpdateLastAppliedIndex(
        flushedEpochs.get(flushedEpochs.size() - 1), -1L, flushedEpochs, true);
  }

  /**
   * Update State machine lastAppliedTermIndex.
   * @param lastFlushedIndex
   * @param currentTerm
   * @param flushedEpochs - list of ratis transactions flushed to DB. If it
   * is just one index and term, this can be set to null.
   * @param checkMap - if true check applyTransactionMap, ratisTransaction
   * Map and update lastAppliedTermIndex accordingly, else check
   * lastAppliedTermIndex and update it.
   */
  private synchronized void computeAndUpdateLastAppliedIndex(
      long lastFlushedIndex, long currentTerm, List<Long> flushedEpochs,
      boolean checkMap) {
    if (checkMap) {
      List<Long> flushedTrans = new ArrayList<>(flushedEpochs);
      Long appliedTerm = null;
      long appliedIndex = -1;
      for (long i = getLastAppliedTermIndex().getIndex() + 1; ; i++) {
        if (flushedTrans.contains(i)) {
          appliedIndex = i;
          final Long removed = applyTransactionMap.remove(i);
          appliedTerm = removed;
          flushedTrans.remove(i);
        } else if (ratisTransactionMap.containsKey(i)) {
          final Long removed = ratisTransactionMap.remove(i);
          appliedTerm = removed;
          appliedIndex = i;
        } else {
          // Add remaining which are left in flushedEpochs to
          // ratisTransactionMap to be considered further.
          for (long epoch : flushedTrans) {
            ratisTransactionMap.put(epoch, applyTransactionMap.remove(epoch));
          }
          if (LOG.isDebugEnabled()) {
            if (!flushedTrans.isEmpty()) {
              LOG.debug("ComputeAndUpdateLastAppliedIndex due to SM added " +
                  "to map remaining {}", flushedTrans);
            }
          }
          break;
        }
      }
      if (appliedTerm != null) {
        updateLastAppliedTermIndex(appliedTerm, appliedIndex);
        if (LOG.isDebugEnabled()) {
          LOG.debug("ComputeAndUpdateLastAppliedIndex due to SM is {}",
              getLastAppliedTermIndex());
        }
      }
    } else {
      if (getLastAppliedTermIndex().getIndex() + 1 == lastFlushedIndex) {
        updateLastAppliedTermIndex(currentTerm, lastFlushedIndex);
        if (LOG.isDebugEnabled()) {
          LOG.debug("ComputeAndUpdateLastAppliedIndex due to notifyIndex {}",
              getLastAppliedTermIndex());
        }
      } else {
        ratisTransactionMap.put(lastFlushedIndex, currentTerm);
        if (LOG.isDebugEnabled()) {
          LOG.debug("ComputeAndUpdateLastAppliedIndex due to notifyIndex " +
              "added to map. Passed Term {} index {}, where as lastApplied " +
              "Index {}", currentTerm, lastFlushedIndex,
              getLastAppliedTermIndex());
        }
      }
    }
    this.metrics.updateApplyTransactionMapSize(applyTransactionMap.size());
    this.metrics.updateRatisTransactionMapSize(ratisTransactionMap.size());
  }

  public void loadSnapshotInfoFromDB() throws IOException {
    // This is done, as we have a check in Ratis for not throwing
    // LeaderNotReadyException, it checks stateMachineIndex >= raftLog
    // nextIndex (placeHolderIndex).
    TransactionInfo transactionInfo =
        TransactionInfo.readTransactionInfo(
            ozoneManager.getMetadataManager());
    if (transactionInfo != null) {
      setLastAppliedTermIndex(TermIndex.valueOf(
          transactionInfo.getTerm(),
          transactionInfo.getTransactionIndex()));
      snapshotInfo.updateTermIndex(transactionInfo.getTerm(),
          transactionInfo.getTransactionIndex());
    }
    LOG.info("LastAppliedIndex is set from TransactionInfo from OM DB as {}",
        getLastAppliedTermIndex());
  }

  /**
   * Submits read request to OM and returns the response Message.
   * @param request OMRequest
   * @return response from OM
   * @throws ServiceException
   */
  private Message queryCommand(OMRequest request) {
    OMResponse response = handler.handleReadRequest(request);
    return OMRatisHelper.convertResponseToMessage(response);
  }

  private static <T> CompletableFuture<T> completeExceptionally(Exception e) {
    final CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(e);
    return future;
  }

  @VisibleForTesting
  public void setHandler(OzoneManagerRequestHandler handler) {
    this.handler = handler;
  }

  @VisibleForTesting
  public OzoneManagerRequestHandler getHandler() {
    return (OzoneManagerRequestHandler) this.handler;
  }

  @VisibleForTesting
  public void setRaftGroupId(RaftGroupId raftGroupId) {
    this.raftGroupId = raftGroupId;
  }

  @VisibleForTesting
  public OzoneManagerStateMachineMetrics getMetrics() {
    return this.metrics;
  }

  public void stop() {
    ozoneManagerDoubleBuffer.stop();
    HadoopExecutors.shutdown(executorService, LOG, 5, TimeUnit.SECONDS);
    HadoopExecutors.shutdown(installSnapshotExecutor, LOG, 5, TimeUnit.SECONDS);
    LOG.info("applyTransactionMap size {} ", applyTransactionMap.size());
    if (LOG.isDebugEnabled()) {
      LOG.debug("applyTransactionMap {}",
          applyTransactionMap.keySet().stream().map(Object::toString)
              .collect(Collectors.joining(",")));
    }
    LOG.info("ratisTransactionMap size {}", ratisTransactionMap.size());
    if (LOG.isDebugEnabled()) {
      LOG.debug("ratisTransactionMap {}",
          ratisTransactionMap.keySet().stream().map(Object::toString)
            .collect(Collectors.joining(",")));
    }
    if (metrics != null) {
      metrics.unRegister();
    }
  }

  @VisibleForTesting
  void addApplyTransactionTermIndex(long term, long index) {
    applyTransactionMap.put(index, term);
  }

  /**
   * Return term associated with transaction index.
   * @param transactionIndex
   * @return
   */
  public long getTermForIndex(long transactionIndex) {
    return applyTransactionMap.get(transactionIndex);
  }

  /**
   * Wait until both buffers are flushed.  This is used in cases like
   * "follower bootstrap tarball creation" where the rocksDb for the active
   * fs needs to synchronized with the rocksdb's for the snapshots.
   */
  public void awaitDoubleBufferFlush() throws InterruptedException {
    ozoneManagerDoubleBuffer.awaitFlush();
  }

  @VisibleForTesting
  public OzoneManagerDoubleBuffer getOzoneManagerDoubleBuffer() {
    return ozoneManagerDoubleBuffer;
  }
}
