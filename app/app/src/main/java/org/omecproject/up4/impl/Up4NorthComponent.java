/*
 SPDX-License-Identifier: Apache-2.0
 SPDX-FileCopyrightText: 2020-present Open Networking Foundation <info@opennetworking.org>
 */
package org.omecproject.up4.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.Server;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.omecproject.up4.Up4Event;
import org.omecproject.up4.Up4EventListener;
import org.omecproject.up4.Up4Service;
import org.omecproject.up4.Up4Translator;
import org.onlab.util.HexString;
import org.onlab.util.ImmutableByteSequence;
import org.onlab.util.SharedExecutors;
import org.onosproject.net.behaviour.upf.UpfCounter;
import org.onosproject.net.behaviour.upf.UpfEntity;
import org.onosproject.net.behaviour.upf.UpfEntityType;
import org.onosproject.net.behaviour.upf.UpfProgrammableException;
import org.onosproject.net.pi.model.DefaultPiPipeconf;
import org.onosproject.net.pi.model.PiCounterId;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.model.PiPipelineModel;
import org.onosproject.net.pi.runtime.PiCounterCell;
import org.onosproject.net.pi.runtime.PiCounterCellId;
import org.onosproject.net.pi.runtime.PiEntity;
import org.onosproject.net.pi.runtime.PiMeterCellConfig;
import org.onosproject.net.pi.runtime.PiTableAction;
import org.onosproject.net.pi.runtime.PiTableEntry;
import org.onosproject.p4runtime.ctl.codec.CodecException;
import org.onosproject.p4runtime.ctl.codec.Codecs;
import org.onosproject.p4runtime.ctl.utils.P4InfoBrowser;
import org.onosproject.p4runtime.ctl.utils.PipeconfHelper;
import org.onosproject.p4runtime.model.P4InfoParser;
import org.onosproject.p4runtime.model.P4InfoParserException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import p4.config.v1.P4InfoOuterClass;
import p4.v1.P4DataOuterClass;
import p4.v1.P4RuntimeGrpc;
import p4.v1.P4RuntimeOuterClass;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.PERMISSION_DENIED;
import static io.grpc.Status.UNIMPLEMENTED;
import static java.lang.String.format;
import static org.omecproject.up4.impl.AppConstants.PIPECONF_ID;
import static org.omecproject.up4.impl.ExtraP4InfoConstants.DDN_DIGEST_ID;
import static org.omecproject.up4.impl.Up4P4InfoConstants.POST_QOS_PIPE_POST_QOS_COUNTER;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_APP_METER;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_PRE_QOS_COUNTER;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_SESSIONS_DOWNLINK;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_SESSIONS_UPLINK;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_SESSION_METER;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_TERMINATIONS_DOWNLINK;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_TERMINATIONS_UPLINK;
import static org.omecproject.up4.impl.Up4P4InfoConstants.PRE_QOS_PIPE_TUNNEL_PEERS;
import static org.onosproject.net.pi.model.PiPipeconf.ExtensionType.P4_INFO_TEXT;


/* TODO: listen for netcfg changes. If the grpc port in the netcfg is different from the default,
         restart the grpc server on the new port.
 */

@Component(immediate = true, service = Up4NorthComponent.class)
public class Up4NorthComponent {
    private static final ImmutableByteSequence ZERO_SEQ = ImmutableByteSequence.ofZeros(4);
    private static final int DEFAULT_DEVICE_ID = 1;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected Up4Service up4Service;

    protected final Up4Translator up4Translator = new Up4TranslatorImpl();
    protected final Up4NorthService up4NorthService = new Up4NorthService();
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Up4EventListener up4EventListener = new InternalUp4EventListener();
    // Stores open P4Runtime StreamChannel(s)
    private final ConcurrentMap<P4RuntimeOuterClass.Uint128,
            StreamObserver<P4RuntimeOuterClass.StreamMessageResponse>> streams =
            Maps.newConcurrentMap();
    private final AtomicInteger ddnDigestListId = new AtomicInteger(0);

    protected P4InfoOuterClass.P4Info p4Info;
    protected PiPipeconf pipeconf;
    private Server server;
    private long pipeconfCookie = 0xbeefbeef;

    public Up4NorthComponent() {
    }

    protected static PiPipeconf buildPipeconf() throws P4InfoParserException {
        final URL p4InfoUrl = Up4NorthComponent.class.getResource(AppConstants.P4INFO_PATH);
        final PiPipelineModel pipelineModel = P4InfoParser.parse(p4InfoUrl);
        return DefaultPiPipeconf.builder()
                .withId(PIPECONF_ID)
                .withPipelineModel(pipelineModel)
                .addExtension(P4_INFO_TEXT, p4InfoUrl)
                .build();
    }

    @Activate
    protected void activate() {
        log.info("Starting...");
        // Load p4info.
        try {
            pipeconf = buildPipeconf();
        } catch (P4InfoParserException e) {
            log.error("Unable to parse UP4 p4info file.", e);
            throw new IllegalStateException("Unable to parse UP4 p4info file.", e);
        }
        p4Info = PipeconfHelper.getP4Info(pipeconf);
        // Start server.
        try {
            server = NettyServerBuilder.forPort(AppConstants.GRPC_SERVER_PORT)
                    .addService(up4NorthService)
                    .build()
                    .start();
            log.info("UP4 gRPC server started on port {}", AppConstants.GRPC_SERVER_PORT);
        } catch (IOException e) {
            log.error("Unable to start gRPC server", e);
            throw new IllegalStateException("Unable to start gRPC server", e);
        }
        // Listen for events.
        up4Service.addListener(up4EventListener);
        log.info("Started.");
    }

    @Deactivate
    protected void deactivate() {
        log.info("Shutting down...");
        up4Service.removeListener(up4EventListener);
        if (server != null) {
            server.shutdown();
        }
        log.info("Stopped.");
    }

    /**
     * Translate the given logical pipeline table entry to a Up4Service entry deletion call.
     *
     * @param entry The logical table entry to be deleted
     * @throws StatusException if the table entry fails translation or cannot be deleted
     */
    private void translateEntryAndDelete(PiTableEntry entry) throws StatusException {
        log.debug("Translating UP4 deletion request to fabric entry deletion.");
        try {
            up4Service.delete(up4Translator.up4TableEntryToUpfEntity(entry));
        } catch (Up4Translator.Up4TranslationException e) {
            log.warn("Failed to translate UP4 entry in deletion request: {}", e.getMessage());
            throw INVALID_ARGUMENT
                    .withDescription("Failed to translate entry in deletion request: " + e.getMessage())
                    .asException();
        } catch (UpfProgrammableException e) {
            log.warn("Failed to complete deletion request: {}", e.getMessage());
            throw io.grpc.Status.UNAVAILABLE
                    .withDescription(e.getMessage())
                    .asException();
        }
    }

    /**
     * Translate the given logical pipeline table entry or meter cell config
     * to a Up4Service entry apply call.
     *
     * @param entry The logical table entry or meter cell config to be applied
     * @throws StatusException if the entry fails translation or cannot be applied
     */
    private void translateEntryAndApply(PiEntity entry) throws StatusException {
        log.debug("Translating UP4 write request to fabric entry.");
        try {
            switch (entry.piEntityType()) {
                case TABLE_ENTRY:
                    PiTableEntry tableEntry = (PiTableEntry) entry;
                    if (tableEntry.action().type() != PiTableAction.Type.ACTION) {
                        log.warn("Action profile entry insertion not supported.");
                        throw UNIMPLEMENTED
                                .withDescription("Action profile entries not supported by UP4.")
                                .asException();
                    }
                    up4Service.apply(up4Translator.up4TableEntryToUpfEntity(tableEntry));
                    break;
                case METER_CELL_CONFIG:
                    up4Service.apply(up4Translator.up4MeterEntryToUpfEntity((PiMeterCellConfig) entry));
                    break;
                default:
                    throw UNIMPLEMENTED
                            .withDescription("Unsupported entity type: " + entry.piEntityType())
                            .asException();
            }
        } catch (Up4Translator.Up4TranslationException e) {
            log.warn("Failed to parse entry from a write request: {}", e.getMessage());
            throw INVALID_ARGUMENT
                    .withDescription("Translation error: " + e.getMessage())
                    .asException();
        } catch (UpfProgrammableException e) {
            log.warn("Failed to complete table entry insertion request: {}", e.getMessage());
            switch (e.getType()) {
                case ENTITY_EXHAUSTED:
                    throw io.grpc.Status.RESOURCE_EXHAUSTED
                            .withDescription(e.getMessage())
                            .asException();
                case ENTITY_OUT_OF_RANGE:
                    throw INVALID_ARGUMENT
                            .withDescription(e.getMessage())
                            .asException();
                case UNKNOWN:
                default:
                    throw io.grpc.Status.UNAVAILABLE
                            .withDescription(e.getMessage())
                            .asException();
            }
        }
    }

    /**
     * Find all table entries or meter entries that match the requested entry,
     * and translate them to p4runtime entities for responding to a read request.
     *
     * @param requestedEntry the entry from a p4runtime read request
     * @return all entries that match the request, translated to p4runtime entities
     * @throws StatusException if the requested entry fails translation
     */
    private List<P4RuntimeOuterClass.Entity> readEntriesAndTranslate(PiEntity requestedEntry)
            throws StatusException {
        List<P4RuntimeOuterClass.Entity> translatedEntries = new ArrayList<>();
        // Respond with all entries for the table or meter of the requested entry, ignoring other requested properties
        // TODO: return more specific responses matching the requested entry
        try {
            UpfEntityType entityType = up4Translator.getEntityType(requestedEntry);
            boolean isMeter = entityType.equals(UpfEntityType.SESSION_METER) ||
                    entityType.equals(UpfEntityType.APPLICATION_METER);
            Collection<? extends UpfEntity> entities = up4Service.readAll(entityType);
            for (UpfEntity entity : entities) {
                log.debug("Translating a {} entity for a read request: {}", entity.type(), entity);
                P4RuntimeOuterClass.Entity responseEntity;
                if (isMeter) {
                    responseEntity = Codecs.CODECS.entity().encode(
                            up4Translator.upfEntityToUp4MeterEntry(entity), null, pipeconf);
                } else {
                    responseEntity = Codecs.CODECS.entity().encode(
                            up4Translator.upfEntityToUp4TableEntry(entity), null, pipeconf);
                }
                translatedEntries.add(responseEntity);
            }
        } catch (Up4Translator.Up4TranslationException | UpfProgrammableException | CodecException e) {
            log.warn("Unable to encode/translate a read entry to a UP4 read response: {}",
                     e.getMessage());
            throw INVALID_ARGUMENT
                    .withDescription("Unable to translate a read table entry to a p4runtime entity.")
                    .asException();
        }
        return translatedEntries;
    }

    /**
     * Update the logical p4info with physical resource sizes.
     *
     * @param p4Info a logical UP4 switch's p4info
     * @return the same p4info, but with resource sizes set to the sizes from the physical switch
     */
    @VisibleForTesting
    P4InfoOuterClass.P4Info setPhysicalSizes(P4InfoOuterClass.P4Info p4Info) {
        var newP4InfoBuilder = P4InfoOuterClass.P4Info.newBuilder(p4Info)
                .clearCounters()
                .clearTables()
                .clearMeters();
        long physicalCounterSize;
        long physicalSessionMeterSize;
        long physicalAppMeterSize;
        long physicalSessionsUlTableSize;
        long physicalSessionsDlTableSize;
        long physicalTerminationsUlTableSize;
        long physicalTerminationsDlTableSize;
        long physicalTunnelPeerTableSize;
        try {
            physicalCounterSize = up4Service.tableSize(UpfEntityType.COUNTER);
            physicalSessionMeterSize = up4Service.tableSize(UpfEntityType.SESSION_METER);
            physicalAppMeterSize = up4Service.tableSize(UpfEntityType.APPLICATION_METER);
            physicalSessionsUlTableSize = up4Service.tableSize(UpfEntityType.SESSION_UPLINK);
            physicalSessionsDlTableSize = up4Service.tableSize(UpfEntityType.SESSION_DOWNLINK);
            physicalTerminationsUlTableSize = up4Service.tableSize(UpfEntityType.TERMINATION_UPLINK);
            physicalTerminationsDlTableSize = up4Service.tableSize(UpfEntityType.TERMINATION_DOWNLINK);
            physicalTunnelPeerTableSize = up4Service.tableSize(UpfEntityType.TUNNEL_PEER);
        } catch (UpfProgrammableException e) {
            throw new IllegalStateException("Error while getting physical sizes! " + e.getMessage());
        }
        int ingressPdrCounterId;
        int egressPdrCounterId;
        int sessionMeterId;
        int appMeterId;
        int sessionsUlTable;
        int sessionsDlTable;
        int terminationsUlTable;
        int terminationsDlTable;
        int tunnelPeerTable;
        try {
            P4InfoBrowser browser = PipeconfHelper.getP4InfoBrowser(pipeconf);
            ingressPdrCounterId = browser.counters()
                    .getByName(PRE_QOS_PIPE_PRE_QOS_COUNTER.id()).getPreamble().getId();
            sessionMeterId = browser.meters()
                    .getByName(PRE_QOS_PIPE_SESSION_METER.id()).getPreamble().getId();
            appMeterId = browser.meters()
                    .getByName(PRE_QOS_PIPE_APP_METER.id()).getPreamble().getId();
            egressPdrCounterId = browser.counters()
                    .getByName(POST_QOS_PIPE_POST_QOS_COUNTER.id()).getPreamble().getId();
            sessionsUlTable = browser.tables()
                    .getByName(PRE_QOS_PIPE_SESSIONS_UPLINK.id()).getPreamble().getId();
            sessionsDlTable = browser.tables()
                    .getByName(PRE_QOS_PIPE_SESSIONS_DOWNLINK.id()).getPreamble().getId();
            terminationsUlTable = browser.tables()
                    .getByName(PRE_QOS_PIPE_TERMINATIONS_UPLINK.id()).getPreamble().getId();
            terminationsDlTable = browser.tables()
                    .getByName(PRE_QOS_PIPE_TERMINATIONS_DOWNLINK.id()).getPreamble().getId();
            tunnelPeerTable = browser.tables()
                    .getByName(PRE_QOS_PIPE_TUNNEL_PEERS.id()).getPreamble().getId();
        } catch (P4InfoBrowser.NotFoundException e) {
            throw new NoSuchElementException("A UP4 counter/table that should always exist does not exist.");
        }
        p4Info.getCountersList().forEach(counter -> {
            if (counter.getPreamble().getId() == ingressPdrCounterId ||
                    counter.getPreamble().getId() == egressPdrCounterId) {
                // Change the sizes of the PDR counters
                newP4InfoBuilder.addCounters(
                        P4InfoOuterClass.Counter.newBuilder(counter)
                                .setSize(physicalCounterSize).build());
            } else {
                // Any other counters go unchanged (for now)
                newP4InfoBuilder.addCounters(counter);
            }
        });
        p4Info.getMetersList().forEach(meter -> {
            if (meter.getPreamble().getId() == sessionMeterId) {
                newP4InfoBuilder.addMeters(
                        P4InfoOuterClass.Meter.newBuilder(meter)
                                .setSize(physicalSessionMeterSize)).build();
            } else if (meter.getPreamble().getId() == appMeterId) {
                newP4InfoBuilder.addMeters(
                        P4InfoOuterClass.Meter.newBuilder(meter)
                                .setSize(physicalAppMeterSize)).build();
            } else {
                // Any other meters go unchanged
                newP4InfoBuilder.addMeters(meter);
            }
        });
        p4Info.getTablesList().forEach(table -> {
            if (table.getPreamble().getId() == sessionsUlTable) {
                newP4InfoBuilder.addTables(
                        P4InfoOuterClass.Table.newBuilder(table)
                                .setSize(physicalSessionsUlTableSize).build());
            } else if (table.getPreamble().getId() == sessionsDlTable) {
                newP4InfoBuilder.addTables(
                        P4InfoOuterClass.Table.newBuilder(table)
                                .setSize(physicalSessionsDlTableSize).build());
            } else if (table.getPreamble().getId() == terminationsUlTable) {
                newP4InfoBuilder.addTables(
                        P4InfoOuterClass.Table.newBuilder(table)
                                .setSize(physicalTerminationsUlTableSize).build());
            } else if (table.getPreamble().getId() == terminationsDlTable) {
                newP4InfoBuilder.addTables(
                        P4InfoOuterClass.Table.newBuilder(table)
                                .setSize(physicalTerminationsDlTableSize).build());
            } else if (table.getPreamble().getId() == tunnelPeerTable) {
                newP4InfoBuilder.addTables(
                        P4InfoOuterClass.Table.newBuilder(table)
                                .setSize(physicalTunnelPeerTableSize).build());
            } else {
                // Any tables aside from the PDR and FAR tables go unchanged
                newP4InfoBuilder.addTables(table);
            }
        });
        return newP4InfoBuilder.build();
    }

    /**
     * Read the all p4 counter cell requested by the message, and translate them to p4runtime
     * entities for crafting a p4runtime read response.
     *
     * @param message a p4runtime CounterEntry message from a read request
     * @return the requested counter cells' contents, as a list of p4runtime entities
     * @throws StatusException if the counter index is out of range
     */
    private List<P4RuntimeOuterClass.Entity> readCountersAndTranslate(P4RuntimeOuterClass.CounterEntry message)
            throws StatusException {
        ArrayList<PiCounterCell> responseCells = new ArrayList<>();
        Integer index = null;
        // FYI a counter read message with no index corresponds to a wildcard read of all indices
        if (message.hasIndex()) {
            index = (int) message.getIndex().getIndex();
        }
        String counterName = null;
        PiCounterId piCounterId = null;
        int counterId = message.getCounterId();
        // FYI a counterId of 0 corresponds to a wildcard read of all counters
        if (counterId != 0) {
            try {
                counterName = PipeconfHelper.getP4InfoBrowser(pipeconf).counters()
                        .getById(message.getCounterId())
                        .getPreamble()
                        .getName();
                piCounterId = PiCounterId.of(counterName);
            } catch (P4InfoBrowser.NotFoundException e) {
                log.warn("Unable to find UP4 counter with ID {}", counterId);
                throw INVALID_ARGUMENT
                        .withDescription("Invalid UP4 counter identifier.")
                        .asException();
            }
        }
        // At this point, the counterName is null if all counters are requested, and non-null if a specific
        //  counter was requested. The index is null if all cells are requested, and non-null if a specific
        //  cell was requested.
        if (counterName != null && index != null) {
            // A single counter cell was requested
            UpfCounter ctrValues;
            try {
                ctrValues = up4Service.readCounter(index);
            } catch (UpfProgrammableException e) {
                throw INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asException();
            }
            long pkts;
            long bytes;
            if (piCounterId.equals(PRE_QOS_PIPE_PRE_QOS_COUNTER)) {
                pkts = ctrValues.getIngressPkts();
                bytes = ctrValues.getIngressBytes();
            } else if (piCounterId.equals(POST_QOS_PIPE_POST_QOS_COUNTER)) {
                pkts = ctrValues.getEgressPkts();
                bytes = ctrValues.getEgressBytes();
            } else {
                log.warn("Received read request for unknown counter {}", piCounterId);
                throw INVALID_ARGUMENT
                        .withDescription("Invalid UP4 counter identifier.")
                        .asException();
            }
            responseCells.add(new PiCounterCell(PiCounterCellId.ofIndirect(piCounterId, index), pkts, bytes));
        } else {
            // All cells were requested, either for a specific counter or all counters
            // FIXME: only read the counter that was requested, instead of both ingress and egress unconditionally
            Collection<UpfCounter> allStats;
            try {
                allStats = up4Service.readCounters(-1);
            } catch (UpfProgrammableException e) {
                throw io.grpc.Status.UNKNOWN.withDescription(e.getMessage()).asException();
            }
            for (UpfCounter stat : allStats) {
                if (piCounterId == null || piCounterId.equals(PRE_QOS_PIPE_PRE_QOS_COUNTER)) {
                    // If all counters were requested, or just the ingress one
                    responseCells.add(new PiCounterCell(
                            PiCounterCellId.ofIndirect(PRE_QOS_PIPE_PRE_QOS_COUNTER, stat.getCellId()),
                            stat.getIngressPkts(), stat.getIngressBytes()));
                }
                if (piCounterId == null || piCounterId.equals(POST_QOS_PIPE_POST_QOS_COUNTER)) {
                    // If all counters were requested, or just the egress one
                    responseCells.add(new PiCounterCell(
                            PiCounterCellId.ofIndirect(POST_QOS_PIPE_POST_QOS_COUNTER, stat.getCellId()),
                            stat.getEgressPkts(), stat.getEgressBytes()));
                }
            }
        }
        List<P4RuntimeOuterClass.Entity> responseEntities = new ArrayList<>();
        for (PiCounterCell cell : responseCells) {
            try {
                responseEntities.add(Codecs.CODECS.entity().encode(cell, null, pipeconf));
                log.trace("Encoded response to counter read request for counter {} and index {}",
                          cell.cellId().counterId(), cell.cellId().index());
            } catch (CodecException e) {
                log.error("Unable to encode counter cell into a p4runtime entity: {}",
                          e.getMessage());
                throw io.grpc.Status.INTERNAL
                        .withDescription("Unable to encode counter cell into a p4runtime entity.")
                        .asException();
            }
        }
        log.debug("Encoded response to counter read request for {} cells", responseEntities.size());
        return responseEntities;
    }

    /**
     * The P4Runtime server service.
     */
    public class Up4NorthService extends P4RuntimeGrpc.P4RuntimeImplBase {

        /**
         * A streamChannel represents a P4Runtime session. This session should persist for the
         * lifetime of a connected controller. The streamChannel is used for master/slave
         * arbitration. Currently this implementation does not track a master, and blindly tells
         * every controller that they are the master as soon as they send an arbitration request. We
         * also do not yet handle anything except arbitration requests.
         *
         * @param responseObserver The thing that is fed responses to arbitration requests.
         * @return A thing that will be fed arbitration requests.
         */
        @Override
        public StreamObserver<P4RuntimeOuterClass.StreamMessageRequest> streamChannel(
                StreamObserver<P4RuntimeOuterClass.StreamMessageResponse> responseObserver) {
            return new StreamObserver<>() {
                // On instance of this class is created for each stream.
                // A stream without electionId is invalid.
                private P4RuntimeOuterClass.Uint128 electionId;

                @Override
                public void onNext(P4RuntimeOuterClass.StreamMessageRequest request) {
                    log.info("Received {} on StreamChannel", request.getUpdateCase());
                    // Arbitration with valid election_id should be the first message
                    if (!request.hasArbitration() && electionId == null) {
                        handleErrorResponse(PERMISSION_DENIED
                                                    .withDescription("Election_id not received for this stream"));
                        return;
                    }
                    switch (request.getUpdateCase()) {
                        case ARBITRATION:
                            handleArbitration(request.getArbitration());
                            return;
                        case PACKET:
                            handlePacketOut(request.getPacket());
                            return;
                        case DIGEST_ACK:
                        case OTHER:
                        case UPDATE_NOT_SET:
                        default:
                            handleErrorResponse(
                                    UNIMPLEMENTED.withDescription(request.getUpdateCase() + " not supported"));
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // No idea what to do here yet.
                    if (t instanceof StatusRuntimeException) {
                        final StatusRuntimeException sre = (StatusRuntimeException) t;
                        final String logMsg;
                        if (sre.getCause() == null) {
                            logMsg = sre.getMessage();
                        } else {
                            logMsg = format("%s (%s)", sre.getMessage(), sre.getCause().toString());
                        }
                        log.warn("StreamChannel error: {}", logMsg);
                        log.debug("", t);
                    } else {
                        log.error("StreamChannel error", t);
                    }
                    if (electionId != null) {
                        streams.remove(electionId);
                    }
                }

                @Override
                public void onCompleted() {
                    log.info("StreamChannel closed");
                    if (electionId != null) {
                        streams.remove(electionId);
                    }
                    responseObserver.onCompleted();
                }

                private void handleArbitration(P4RuntimeOuterClass.MasterArbitrationUpdate request) {
                    if (request.getDeviceId() != DEFAULT_DEVICE_ID) {
                        handleErrorResponse(INVALID_ARGUMENT
                                                    .withDescription("Invalid device_id"));
                        return;
                    }
                    if (!P4RuntimeOuterClass.Role.getDefaultInstance().equals(request.getRole())) {
                        handleErrorResponse(UNIMPLEMENTED
                                                    .withDescription("Role config not supported"));
                        return;
                    }
                    if (P4RuntimeOuterClass.Uint128.getDefaultInstance()
                            .equals(request.getElectionId())) {
                        handleErrorResponse(INVALID_ARGUMENT
                                                    .withDescription("Missing election_id"));
                        return;
                    }
                    streams.compute(request.getElectionId(), (requestedElectionId, storedResponseObserver) -> {
                        if (storedResponseObserver == null) {
                            // All good.
                            this.electionId = requestedElectionId;
                            log.info("Blindly telling requester with election_id {} they are the primary controller",
                                     TextFormat.shortDebugString(this.electionId));
                            // FIXME: implement election_id handling
                            responseObserver.onNext(
                                    P4RuntimeOuterClass.StreamMessageResponse.newBuilder()
                                            .setArbitration(
                                                    P4RuntimeOuterClass.MasterArbitrationUpdate.newBuilder()
                                                            .setDeviceId(request.getDeviceId())
                                                            .setRole(request.getRole())
                                                            .setElectionId(this.electionId)
                                                            .setStatus(
                                                                    Status.newBuilder()
                                                                            .setCode(Code.OK.getNumber())
                                                                            .build()
                                                            ).build()
                                            ).build());
                            // Store in map.
                            return responseObserver;
                        } else if (responseObserver != storedResponseObserver) {
                            handleErrorResponse(
                                    INVALID_ARGUMENT.withDescription("Election_id already in use by another client"));
                            // Map value unchanged.
                            return storedResponseObserver;
                        } else {
                            // Client is sending a second arbitration request for the same or a new
                            // election_id. Not supported.
                            handleErrorResponse(
                                    UNIMPLEMENTED.withDescription("Update of master arbitration not supported"));
                            // Remove from map.
                            return null;
                        }
                    });
                }

                private void handlePacketOut(P4RuntimeOuterClass.PacketOut request) {
                    try {
                        errorIfSwitchNotReady();
                        if (request.getPayload().isEmpty()) {
                            log.error("Received packet-out with empty payload");
                            return;
                        }
                        final byte[] frame = request.getPayload().toByteArray();
                        if (log.isDebugEnabled()) {
                            log.debug("Sending packet-out: {}", HexString.toHexString(frame, " "));
                        }
                        up4Service.sendPacketOut(ByteBuffer.wrap(frame));
                    } catch (StatusException e) {
                        // Drop exception to avoid closing the stream.
                        log.error("Unable to send packet-out: {}", e.getMessage());
                    } catch (UpfProgrammableException e) {
                        log.error(e.getMessage());
                    }
                }

                private void handleErrorResponse(io.grpc.Status status) {
                    log.warn("Closing StreamChannel with client: {}", status.toString());
                    responseObserver.onError(status.asException());
                    // Remove stream from map.
                    if (electionId != null) {
                        streams.computeIfPresent(electionId, (storedElectionId, storedResponseObserver) -> {
                            if (responseObserver == storedResponseObserver) {
                                // Remove.
                                return null;
                            } else {
                                // This is another stream with same election_id. Do not remove.
                                return storedResponseObserver;
                            }
                        });
                    }
                }
            };
        }

        /**
         * Receives a pipeline config from a client. We don't support this feature
         * in UP4. UP4 has a pre-configured pipeline config, that is populated
         * at runtime with sizes coming from the data plane.
         *
         * @param request          A request containing a p4info and cookie
         * @param responseObserver The thing that is fed a response to the config request.
         */
        @Override
        public void setForwardingPipelineConfig(P4RuntimeOuterClass.SetForwardingPipelineConfigRequest request,
                                                StreamObserver<P4RuntimeOuterClass.SetForwardingPipelineConfigResponse>
                                                        responseObserver) {
            log.info("Attempted setForwardingPipelineConfig, not supported in UP4");
            responseObserver.onError(
                    io.grpc.Status.UNIMPLEMENTED
                            .withDescription("setForwardingPipelineConfig not supported in UP4")
                            .asException()
            );
        }

        /**
         * Returns the UP4 logical switch p4info (but with physical resource sizes) and cookie.
         *
         * @param request          A request for a forwarding pipeline config
         * @param responseObserver The thing that is fed the pipeline config response.
         */
        @Override
        public void getForwardingPipelineConfig(P4RuntimeOuterClass.GetForwardingPipelineConfigRequest request,
                                                StreamObserver<P4RuntimeOuterClass.GetForwardingPipelineConfigResponse>
                                                        responseObserver) {
            try {
                errorIfSwitchNotReady();
                responseObserver.onNext(
                        P4RuntimeOuterClass.GetForwardingPipelineConfigResponse.newBuilder().setConfig(
                                P4RuntimeOuterClass.ForwardingPipelineConfig.newBuilder()
                                        .setCookie(P4RuntimeOuterClass.ForwardingPipelineConfig.Cookie.newBuilder()
                                                           .setCookie(pipeconfCookie))
                                        .setP4Info(setPhysicalSizes(p4Info))
                                        .build())
                                .build());
                responseObserver.onCompleted();
            } catch (StatusException e) {
                // FIXME: make it p4rt-compliant
                // From P4RT specs: "If a P4Runtime server is in a state where
                //  the forwarding-pipeline config is not known, the top-level config
                //  field will be unset in the response. Examples are (i) a server
                //  that only allows configuration via SetForwardingPipelineConfig
                //  but this RPC hasn't been invoked yet, (ii) a server that is
                //  configured using a different mechanism but this configuration
                //  hasn't yet occurred." - (ii) is the UP4 case -.
                // So, we shouldn't return an error, but simply set an empty config.
                // However, we do return an error in this way it's easier for
                // pfcp-agent to manage this case.
                responseObserver.onError(e);
            }
        }


        private void errorIfSwitchNotReady() throws StatusException {
            if (!up4Service.configIsLoaded()) {
                log.warn("UP4 client attempted to read or write to logical switch before an app config was loaded.");
                throw io.grpc.Status.FAILED_PRECONDITION
                        .withDescription("App config not loaded.")
                        .asException();
            }
            if (!up4Service.isReady()) {
                log.warn("UP4 client attempted to read or write to logical switch " +
                                 "while the physical device was unavailable.");
                throw io.grpc.Status.FAILED_PRECONDITION
                        .withDescription("Physical switch unavailable.")
                        .asException();
            }
            if (p4Info == null) {
                log.warn("Read or write request received before pipeline config set.");
                throw io.grpc.Status.FAILED_PRECONDITION
                        .withDescription("Switch pipeline not set.")
                        .asException();
            }
        }

        private void doWrite(P4RuntimeOuterClass.WriteRequest request,
                             StreamObserver<P4RuntimeOuterClass.WriteResponse> responseObserver)
                throws StatusException {
            for (P4RuntimeOuterClass.Update update : request.getUpdatesList()) {
                if (!update.hasEntity()) {
                    log.warn("Update message with no entities received. Ignoring");
                    continue;
                }
                P4RuntimeOuterClass.Entity requestEntity = update.getEntity();
                switch (requestEntity.getEntityCase()) {
                    case COUNTER_ENTRY:
                        // TODO: support counter cell writes, including wildcard writes
                        break;
                    case METER_ENTRY:
                        PiEntity piMeterEntity;
                        try {
                            piMeterEntity = Codecs.CODECS.entity().decode(requestEntity, null, pipeconf);
                        } catch (CodecException e) {
                            log.warn("Unable to decode p4runtime entity update message", e);
                            throw INVALID_ARGUMENT.withDescription(e.getMessage()).asException();
                        }
                        PiMeterCellConfig meterEntry = (PiMeterCellConfig) piMeterEntity;
                        if (update.getType() == P4RuntimeOuterClass.Update.Type.MODIFY) {
                            // The only operation supported for meters is MODIFY
                            translateEntryAndApply(meterEntry);
                        } else {
                            log.error("Unsupported update type for meter entry!");
                            throw INVALID_ARGUMENT
                                    .withDescription("Unsupported update type")
                                    .asException();
                        }
                        break;
                    case TABLE_ENTRY:
                        PiEntity piTableEntity;
                        try {
                            piTableEntity = Codecs.CODECS.entity().decode(requestEntity, null, pipeconf);
                        } catch (CodecException e) {
                            log.warn("Unable to decode p4runtime entity update message", e);
                            throw INVALID_ARGUMENT.withDescription(e.getMessage()).asException();
                        }
                        PiTableEntry entry = (PiTableEntry) piTableEntity;
                        switch (update.getType()) {
                            case INSERT:
                            case MODIFY:
                                translateEntryAndApply(entry);
                                break;
                            case DELETE:
                                translateEntryAndDelete(entry);
                                break;
                            default:
                                log.warn("Unsupported update type for a table entry");
                                throw INVALID_ARGUMENT
                                        .withDescription("Unsupported update type")
                                        .asException();
                        }
                        break;
                    default:
                        log.warn("Received write request for unsupported entity type {}",
                                 requestEntity.getEntityCase());
                        throw INVALID_ARGUMENT
                                .withDescription("Unsupported entity type")
                                .asException();
                }
            }
            // Response is currently defined to be empty per p4runtime.proto
            responseObserver.onNext(P4RuntimeOuterClass.WriteResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }


        /**
         * Writes entities to the logical UP4 switch.
         *
         * @param request          A request containing entities to be written
         * @param responseObserver The thing that is fed a response once writing has concluded.
         */
        @Override
        public void write(P4RuntimeOuterClass.WriteRequest request,
                          StreamObserver<P4RuntimeOuterClass.WriteResponse> responseObserver) {
            log.debug("Received write request.");
            try {
                errorIfSwitchNotReady();
                doWrite(request, responseObserver);
            } catch (StatusException e) {
                responseObserver.onError(e);
            }
            log.debug("Done with write request.");
        }

        private void doRead(P4RuntimeOuterClass.ReadRequest request,
                            StreamObserver<P4RuntimeOuterClass.ReadResponse> responseObserver)
                throws StatusException {
            for (P4RuntimeOuterClass.Entity requestEntity : request.getEntitiesList()) {
                switch (requestEntity.getEntityCase()) {
                    case COUNTER_ENTRY:
                        responseObserver.onNext(
                                P4RuntimeOuterClass.ReadResponse.newBuilder()
                                        .addAllEntities(readCountersAndTranslate(requestEntity.getCounterEntry()))
                                        .build());
                        break;
                    case METER_ENTRY:
                    case TABLE_ENTRY:
                        PiEntity requestEntry;
                        try {
                            requestEntry = Codecs.CODECS.entity().decode(
                                    requestEntity, null, pipeconf);
                        } catch (CodecException e) {
                            log.warn("Unable to decode p4runtime read request entity", e);
                            throw INVALID_ARGUMENT.withDescription(e.getMessage()).asException();
                        }
                        responseObserver.onNext(
                                P4RuntimeOuterClass.ReadResponse.newBuilder()
                                        .addAllEntities(readEntriesAndTranslate(requestEntry))
                                        .build());
                        break;
                    default:
                        log.warn("Received read request for an entity we don't yet support. Skipping");
                        break;
                }
            }
            responseObserver.onCompleted();
        }

        /**
         * Reads entities from the logical UP4 switch. Currently only supports counter reads.
         *
         * @param request          A request containing one or more entities to be read.
         * @param responseObserver Thing that will be fed descriptions of the requested entities.
         */
        @Override
        public void read(P4RuntimeOuterClass.ReadRequest request,
                         StreamObserver<P4RuntimeOuterClass.ReadResponse> responseObserver) {
            log.debug("Received read request.");
            try {
                errorIfSwitchNotReady();
                doRead(request, responseObserver);
            } catch (StatusException e) {
                responseObserver.onError(e);
            }
            log.debug("Done with read request.");
        }
    }

    private void handleDdn(Up4Event event) {
        if (event.subject().ueAddress() == null) {
            log.error("Received {} but UE address is missing, bug?", event.type());
            return;
        }
        var digestList = P4RuntimeOuterClass.DigestList.newBuilder()
                .setDigestId(DDN_DIGEST_ID)
                .setListId(ddnDigestListId.incrementAndGet())
                .addData(P4DataOuterClass.P4Data.newBuilder()
                                 .setBitstring(ByteString.copyFrom(event.subject().ueAddress().toOctets()))
                                 .build())
                .build();
        var msg = P4RuntimeOuterClass.StreamMessageResponse.newBuilder()
                .setDigest(digestList).build();
        if (streams.isEmpty()) {
            log.warn("There are no clients connected, dropping {} for UE address {}",
                     event.type(), event.subject().ueAddress());
        } else {
            streams.forEach((electionId, responseObserver) -> {
                log.debug("Sending DDN digest to client with election_id {}: {}",
                          TextFormat.shortDebugString(electionId), TextFormat.shortDebugString(msg));
                responseObserver.onNext(msg);
            });
        }
    }

    class InternalUp4EventListener implements Up4EventListener {

        @Override
        public void event(Up4Event event) {
            if (event.type() == Up4Event.Type.DOWNLINK_DATA_NOTIFICATION) {
                SharedExecutors.getPoolThreadExecutor()
                        .execute(() -> handleDdn(event));
            }
        }
    }
}
