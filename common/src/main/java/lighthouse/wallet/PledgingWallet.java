package lighthouse.wallet;

import com.google.bitcoin.core.*;
import com.google.bitcoin.crypto.*;
import com.google.bitcoin.params.UnitTestParams;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.bitcoin.store.WalletProtobufSerializer;
import com.google.bitcoin.utils.ListenerRegistration;
import com.google.bitcoin.wallet.*;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.Project;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.*;
import static lighthouse.protocol.LHUtils.*;

/**
 * A pledging wallet is a customization of the normal Wallet class that knows how to form, track, serialize and undo
 * pledges to various projects.
 */
public class PledgingWallet extends Wallet {
    private static final Logger log = LoggerFactory.getLogger(PledgingWallet.class);

    @GuardedBy("this") private final BiMap<TransactionOutput, LHProtos.Pledge> pledges;
    @GuardedBy("this") private final BiMap<Project, LHProtos.Pledge> projects;
    // See the wallet-extension.proto file for a discussion of why we track these.
    @GuardedBy("this") private final Map<Sha256Hash, LHProtos.Pledge> revokedPledges;

    public interface OnPledgeHandler {
        public void onPledge(Project project, LHProtos.Pledge data);
    }
    public interface OnRevokeHandler {
        public void onRevoke(LHProtos.Pledge pledge);
    }
    public interface OnClaimHandler {
        public void onClaim(LHProtos.Pledge pledge, Transaction claimTX);
    }

    private CopyOnWriteArrayList<ListenerRegistration<OnPledgeHandler>> onPledgeHandlers = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<OnRevokeHandler>> onRevokeHandlers = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<ListenerRegistration<OnClaimHandler>> onClaimedHandlers = new CopyOnWriteArrayList<>();

    public PledgingWallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
        super(params, keyChainGroup);
        setCoinSelector(new IgnorePledgedCoinSelector());
        pledges = HashBiMap.create();
        projects = HashBiMap.create();
        revokedPledges = new HashMap<>();
        addExtension(new PledgeStorage(this));
    }

    public PledgingWallet(NetworkParameters params) {
        this(params, new KeyChainGroup(params));
    }

    // This has to be a static class, as it must be instantiated BEFORE the wallet is created so it can be passed
    // to the deserializer.
    public static class PledgeStorage implements WalletExtension {
        public PledgingWallet wallet;

        public PledgeStorage(@Nullable PledgingWallet wallet) {
            // Can be null if this was created prior to deserialization: we'll be given the wallet later in that case.
            this.wallet = wallet;
        }

        @Override
        public String getWalletExtensionID() {
            return "com.vinumeris.lighthouse";
        }

        @Override
        public boolean isWalletExtensionMandatory() {
            // Allow other apps to open these wallets. Of course those wallets will be downgraded automatically and
            // may have their pledges messed up/revoked, but in a pinch it may be a good way to recover money from
            // a bad install (e.g. if CC crashes at startup for some reason).
            return false;
        }

        @Override
        public byte[] serializeWalletExtension() {
            LHWalletProtos.Extension.Builder ext = LHWalletProtos.Extension.newBuilder();
            wallet.populateExtensionProto(ext);
            return ext.build().toByteArray();
        }

        @SuppressWarnings("FieldAccessNotGuarded")  // No need to synchronize when deserializing a new wallet.
        @Override
        public void deserializeWalletExtension(Wallet containingWallet, byte[] data) throws Exception {
            wallet = (PledgingWallet) containingWallet;
            LHWalletProtos.Extension ext = LHWalletProtos.Extension.parseFrom(data);
            log.info("Wallet has {} pledges in it", ext.getPledgesCount());
            Map<TransactionOutput, LHProtos.Pledge> contractOuts = new HashMap<>();
            for (LHProtos.Pledge pledge : ext.getPledgesList()) {
                final List<ByteString> txns = pledge.getTransactionsList();
                // The pledge must be the only tx.
                Transaction tx = new Transaction(wallet.params, txns.get(txns.size() - 1).toByteArray());
                if (tx.getInputs().size() != 1) {
                    log.error("Pledge TX does not seem to have the right form: {}", tx);
                    continue;
                }
                // Find the stub output that the pledge spends.
                final TransactionOutPoint op = tx.getInput(0).getOutpoint();
                final Transaction transaction = wallet.transactions.get(op.getHash());
                checkNotNull(transaction);
                TransactionOutput output = transaction.getOutput((int) op.getIndex());
                checkNotNull(output);
                // Record the contract output it pledges to.
                contractOuts.put(tx.getOutput(0), pledge);
                log.info("Loaded pledge {}", pledge);
                wallet.pledges.put(output, pledge);
            }
            for (LHProtos.Project project : ext.getProjectsList()) {
                Project p = new Project(project);
                LHProtos.Pledge pledgeForProject = contractOuts.get(p.getOutputs().get(0));
                wallet.projects.put(p, pledgeForProject);
            }
            for (LHProtos.Pledge pledge : ext.getRevokedPledgesList()) {
                wallet.revokedPledges.put(hashFromPledge(pledge), pledge);
            }
        }

        @Override
        public String toString() {
            return wallet.pledgesToString();
        }
    }

    private synchronized String pledgesToString() {
        StringBuilder builder = new StringBuilder();
        BiMap<LHProtos.Pledge, Project> mapPledgeProject = projects.inverse();
        for (LHProtos.Pledge pledge : pledges.values()) {
            builder.append(String.format("Pledge:%n%s%nTotal input value: %d%nFor project: %s%n%n",
                    new Transaction(params, pledge.getTransactions(0).toByteArray()),
                    pledge.getTotalInputValue(),
                    mapPledgeProject.get(pledge)));
        }
        return builder.toString();
    }

    public class PendingPledge {
        @Nullable public final Transaction dependency;
        public final Transaction pledge;
        public final long feesRequired;
        public final Project project;

        private boolean committed = false;

        public PendingPledge(Project project, @Nullable Transaction dependency, Transaction pledge, long feesRequired) {
            this.project = project;
            this.dependency = dependency;
            this.pledge = pledge;
            this.feesRequired = feesRequired;
        }

        public LHProtos.Pledge getData() {
            final TransactionOutput stub = pledge.getInput(0).getConnectedOutput();
            checkNotNull(stub);
            LHProtos.Pledge.Builder proto = LHProtos.Pledge.newBuilder();
            if (dependency != null)
                proto.addTransactions(ByteString.copyFrom(dependency.bitcoinSerialize()));
            proto.addTransactions(ByteString.copyFrom(pledge.bitcoinSerialize()));
            proto.setTotalInputValue(stub.getValue().longValue());
            proto.setTimestamp(Utils.currentTimeSeconds());
            proto.setProjectId(project.getID());
            return proto.build();
        }

        public LHProtos.Pledge commit(boolean andBroadcastDependencies) {
            // Commit and broadcast the dependency.
            LHProtos.Pledge data = getData();
            return commit(andBroadcastDependencies, data);
        }

        public LHProtos.Pledge commit(boolean andBroadcastDependencies, LHProtos.Pledge data) {
            final TransactionOutput stub = pledge.getInput(0).getConnectedOutput();
            checkNotNull(stub);
            checkState(!committed);
            log.info("Committing pledge for stub: {}", stub);
            committed = true;
            if (dependency != null && andBroadcastDependencies) {
                commitTx(dependency);
                log.info("Committing and broadcasting dependency");
                vTransactionBroadcaster.broadcastTransaction(dependency);
            }
            log.info("Pledge has {} txns", data.getTransactionsCount());
            Coin prevBalance = getBalance();
            updateForPledge(data, project, stub);
            saveNow();
            for (ListenerRegistration<OnPledgeHandler> handler : onPledgeHandlers) {
                handler.executor.execute(() -> handler.listener.onPledge(project, data));
            }
            lock.lock();
            try {
                queueOnCoinsSent(pledge, prevBalance, getBalance());
                maybeQueueOnWalletChanged();
            } finally {
                lock.unlock();
            }
            return data;
        }
    }

    public org.bitcoinj.wallet.Protos.Wallet serialize() {
        WalletProtobufSerializer serializer = new WalletProtobufSerializer();
        return serializer.walletToProto(this);
    }

    public static PledgingWallet deserialize(org.bitcoinj.wallet.Protos.Wallet proto) throws UnreadableWalletException {
        WalletProtobufSerializer serializer = new WalletProtobufSerializer(PledgingWallet::new);
        NetworkParameters params = NetworkParameters.fromID(proto.getNetworkIdentifier());
        return (PledgingWallet) serializer.readWallet(params, new WalletExtension[]{new PledgeStorage(null)}, proto);
    }

    private synchronized void populateExtensionProto(LHWalletProtos.Extension.Builder ext) {
        ext.addAllPledges(pledges.values());
        ext.addAllProjects(projects.keySet().stream().map(Project::getProto).collect(Collectors.toList()));
        ext.addAllRevokedPledges(revokedPledges.values());
    }

    private synchronized void updateForPledge(LHProtos.Pledge data, Project project, TransactionOutput stub) {
        pledges.put(stub, data);
        projects.put(project, data);
    }

    public PendingPledge createPledge(Project project, long satoshis, KeyParameter aesKey) throws InsufficientMoneyException {
        return createPledge(project, Coin.valueOf(satoshis), aesKey);
    }

    public PendingPledge createPledge(Project project, Coin value, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        checkNotNull(project);
        // Attempt to find a single output that can satisfy this given pledge, because pledges cannot have change
        // outputs, and submitting multiple inputs is unfriendly (increases fees paid by the pledge claimer).
        // This process takes into account outputs that are already pledged, to ignore them. We call a pledged output
        // the "stub" and the tx that spends it using SIGHASH_ANYONECANPAY the "pledge". The template tx outputs are
        // the "contract".

        TransactionOutput stub = findAvailableStub(value);
        log.info("First attempt to find a stub yields: {}", stub);

        // If no such output exists, we must create a tx that creates an output of the right size and then try again.
        Coin totalFees = Coin.ZERO;
        Transaction dependency = null;
        if (stub == null) {
            final Address stubAddr = currentReceiveKey().toAddress(getParams());
            SendRequest req;
            if (value.equals(getBalance()))
                req = SendRequest.emptyWallet(stubAddr);
            else
                req = SendRequest.to(stubAddr, value);
            if (params == UnitTestParams.get())
                req.shuffleOutputs = false;
            req.aesKey = aesKey;
            completeTx(req);
            dependency = req.tx;
            totalFees = req.fee;
            log.info("Created dependency tx {}", dependency.getHash());
            // The change is in a random output position so we have to search for it. It's possible that there are
            // two outputs of the same size, in that case it doesn't matter which we use.
            stub = findOutputOfValue(value, dependency.getOutputs());
            if (stub == null) {
                // We created a dependency tx to make a stub, and now we can't find it. This can only happen
                // if we are sending the entire balance and thus had to subtract the miner fee from the value.
                checkState(req.emptyWallet);
                checkState(dependency.getOutputs().size() == 1);
                stub = dependency.getOutput(0);
            }
        }
        checkNotNull(stub);

        Transaction pledge = new Transaction(getParams());
        // TODO: Support submitting multiple inputs in a single pledge tx here.
        TransactionInput input = pledge.addInput(stub);
        project.getOutputs().forEach(pledge::addOutput);
        ECKey key = input.getOutpoint().getConnectedKey(this);
        checkNotNull(key);
        Script script = stub.getScriptPubKey();
        if (aesKey != null)
            key = key.maybeDecrypt(aesKey);
        TransactionSignature signature = pledge.calculateSignature(0, key, script,
                Transaction.SigHash.ALL, true /* anyone can pay! */);
        if (script.isSentToAddress()) {
            input.setScriptSig(ScriptBuilder.createInputScript(signature, key));
        } else if (script.isSentToRawPubKey()) {
            // This branch will never be taken with the current design of the app because the only way to get money
            // in is via an address, but in future we might support direct-to-key payments via the payment protocol.
            input.setScriptSig(ScriptBuilder.createInputScript(signature));
        }
        input.setScriptSig(ScriptBuilder.createInputScript(signature,  key));
        pledge.setPurpose(Transaction.Purpose.ASSURANCE_CONTRACT_PLEDGE);

        log.info("Paid {} satoshis in fees to create pledge tx {}", totalFees, pledge);

        return new PendingPledge(project, dependency, pledge, totalFees.longValue());
    }

    @Nullable
    public synchronized LHProtos.Pledge getPledgeFor(Project project) {
        return projects.get(project);
    }

    public long getPledgedAmountFor(Project project) {
        LHProtos.Pledge pledge = getPledgeFor(project);
        return pledge == null ? 0 : pledge.getTotalInputValue();
    }

    // Returns a spendable output of exactly the given value.
    @Nullable
    private TransactionOutput findAvailableStub(Coin value) {
        CoinSelection selection = coinSelector.select(value, calculateAllSpendCandidates(true));
        if (selection.valueGathered.compareTo(value) < 0)
            return null;
        return findOutputOfValue(value, selection.gathered);
    }

    private TransactionOutput findOutputOfValue(Coin value, Collection<TransactionOutput> outputs) {
        return outputs.stream()
                      .filter(out -> out.getValue().equals(value))
                      .findFirst()
                      .orElse(null);
    }

    public synchronized Set<LHProtos.Pledge> getPledges() {
        return new HashSet<>(pledges.values());
    }

    public synchronized boolean wasPledgeRevoked(LHProtos.Pledge pledge) {
        final Sha256Hash hash = hashFromPledge(pledge);
        return revokedPledges.containsKey(hash);
    }

    @GuardedBy("this") private Set<Transaction> revokeInProgress = new HashSet<>();

    public class Revocation {
        public final ListenableFuture<Transaction> broadcastFuture;
        public final Transaction tx;

        public Revocation(ListenableFuture<Transaction> broadcastFuture, Transaction tx) {
            this.broadcastFuture = broadcastFuture;
            this.tx = tx;
        }
    }

    /**
     * Given a pledge protobuf, double spends the stub so the pledge can no longer be claimed. The pledge is
     * removed from the wallet once the double spend propagates successfully.
     *
     * @throws com.google.bitcoin.core.InsufficientMoneyException if we can't afford the double spend tx fee.
     */
    public Revocation revokePledge(LHProtos.Pledge proto, @Nullable KeyParameter aesKey) throws InsufficientMoneyException {
        TransactionOutput stub;
        synchronized (this) {
            stub = pledges.inverse().get(proto);
        }
        checkArgument(stub != null, "Given pledge not found: %s", proto);
        Transaction revocation = new Transaction(params);
        revocation.addInput(stub);
        // Send all pledged amount back to a fresh address minus the fee amount.
        revocation.addOutput(stub.getValue().subtract(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE),
                freshReceiveKey().toAddress(params));
        SendRequest request = SendRequest.forTx(revocation);
        request.aesKey = aesKey;
        completeTx(request);
        synchronized (this) {
            revokeInProgress.add(request.tx);
        }
        log.info("Broadcasting revocation of pledge for {} satoshis", stub.getValue());
        log.info("Stub: {}", stub);
        log.info("Revocation tx: {}", revocation);
        final ListenableFuture<Transaction> future = vTransactionBroadcaster.broadcastTransaction(revocation);
        Futures.addCallback(future, new FutureCallback<Transaction>() {
            @Override
            public void onSuccess(@Nullable Transaction result) {
                final Sha256Hash hash = hashFromPledge(proto);
                log.info("Broadcast of revocation was successful, marking pledge {} as revoked in wallet", hash);
                log.info("Pledge has {} txns", proto.getTransactionsCount());
                updateForRevoke(hash, result, proto, stub);
                saveNow();
                for (ListenerRegistration<OnRevokeHandler> handler : onRevokeHandlers) {
                    handler.executor.execute(() -> handler.listener.onRevoke(proto));
                }
                lock.lock();
                try {
                    maybeQueueOnWalletChanged();
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to broadcast pledge revocation: {}", t);
            }
        });
        return new Revocation(future, revocation);
    }

    private synchronized void updateForRevoke(Sha256Hash hash, Transaction tx, LHProtos.Pledge proto, TransactionOutput stub) {
        revokeInProgress.remove(tx);
        revokedPledges.put(hash, proto);
        pledges.remove(stub);
        projects.inverse().remove(proto);
    }

    /**
     * Runs completeContract to get a feeless contract, then attaches an extra input of size MIN_FEE, potentially
     * creating and broadcasting a tx to create an output of the right size first (as we cannot add change outputs
     * to an assurance contract). The returned future completes once both dependency and contract are broadcast OK.
     */
    public CompletableFuture<Transaction> completeContractWithFee(Project project, Set<LHProtos.Pledge> pledges) throws InsufficientMoneyException {
        // The chances of having a fee shaped output are minimal, so we always create a dependency tx here.
        final Coin feeSize = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        log.info("Completing contract with fee: sending dependency tx");
        Transaction contract = project.completeContract(pledges);
        Wallet.SendResult result = sendCoins(vTransactionBroadcaster, freshReceiveKey().toAddress(params), feeSize);
        // The guava API is better for this than what the Java 8 guys produced, sigh.
        ListenableFuture<Transaction> future = Futures.transform(result.broadcastComplete, (AsyncFunction<Transaction, Transaction>) tx -> {
            // Runs on a bitcoinj thread when the dependency was broadcast.
            // Find the right output size and add it as a regular input (that covers the rest).
            log.info("Dependency broadcast complete");
            TransactionOutput feeOut = tx.getOutputs().stream()
                    .filter(output -> output.getValue().equals(feeSize)).findAny().get();
            contract.addInput(feeOut);
            // Sign the final output we added.
            signTransaction(Wallet.SendRequest.forTx(contract));
            log.info("Prepared final contract: {}", contract);
            return vTransactionBroadcaster.broadcastTransaction(contract);
        });
        return convertFuture(future);
    }

    public void addOnPledgeHandler(OnPledgeHandler onPledgeHandler, Executor executor) {
        onPledgeHandlers.add(new ListenerRegistration<>(onPledgeHandler, executor));
    }

    public void addOnRevokeHandler(OnRevokeHandler onRevokeHandler, Executor executor) {
        onRevokeHandlers.add(new ListenerRegistration<>(onRevokeHandler, executor));
    }

    public void addOnClaimHandler(OnClaimHandler onClaimHandler, Executor executor) {
        onClaimedHandlers.add(new ListenerRegistration<>(onClaimHandler, executor));
    }

    private class IgnorePledgedCoinSelector extends DefaultCoinSelector {
        @Override
        protected boolean shouldSelect(Transaction tx) {
            return true;   // Allow spending of pending transactions.
        }

        @Override
        public CoinSelection select(Coin target, List<TransactionOutput> candidates) {
            // Remove all the outputs pledged already before selecting.
            synchronized (PledgingWallet.this) {
                //noinspection FieldAccessNotGuarded
                candidates.removeAll(pledges.keySet());
            }
            // Search for a perfect match first, to see if we can avoid creating a dependency transaction.
            for (TransactionOutput op : candidates)
                if (op.getValue().equals(target))
                    return new CoinSelection(op.getValue(), ImmutableList.of(op));
            // Delegate back to the default behaviour.
            return super.select(target, candidates);
        }
    }

    @Override
    protected void queueOnCoinsSent(Transaction tx, Coin prevBalance, Coin newBalance) {
        super.queueOnCoinsSent(tx, prevBalance, newBalance);

        // Check to see if we just saw a pledge get spent.
        synchronized (this) {
            for (Map.Entry<TransactionOutput, LHProtos.Pledge> entry : pledges.entrySet()) {
                TransactionInput spentBy = entry.getKey().getSpentBy();
                if (spentBy != null && tx.equals(spentBy.getParentTransaction())) {
                    if (!revokeInProgress.contains(tx)) {
                        log.info("Saw spend of our pledge that we didn't revoke ... ");
                        LHProtos.Pledge pledge = entry.getValue();
                        Project project = projects.inverse().get(pledge);
                        checkNotNull(project);
                        if (compareOutputsStructurally(tx, project)) {
                            log.info("... by a tx matching the project's outputs: claimed!");
                            for (ListenerRegistration<OnClaimHandler> handler : onClaimedHandlers) {
                                handler.executor.execute(() -> handler.listener.onClaim(pledge, tx));
                            }
                        } else {
                            log.warn("... by a tx we don't recognise: cloned wallet?");
                        }
                    }
                }
            }
        }
    }

    /** Returns new key that can be used to prove we created a particular project. */
    public DeterministicKey freshAuthKey() {
        // We use a fresh key here each time for a bit of extra privacy so there's no way to link projects together.
        // However this does yield a problem: if we were to create more projects than exist in the lookahead zone,
        // and then the user restores their wallet from a seed, they might not notice they own the project in question.
        // We could fix this by including the key index into the project file, but then we're back to leaking personal
        // data (this time: how many projects you created, even though they're somewhat unlinkable). The best solution
        // would be to store the key path but encrypted under a constant key e.g. the first internal key. But I don't
        // have time to implement this now: people who create lots of projects and then restore from seed (not the
        // recommended way to back up your wallet) may need manual rescue
        return freshKey(KeyChain.KeyPurpose.AUTHENTICATION);
    }

    /**
     * Given pubkey/index pair, returns the given DeterministicKey object. Index will typically be -1 unless the
     * generated auth key (above) was beyond the lookahead threshold, in which case we must record the index in the
     * project file and use it here to find the right key in case the user restored from wallet seed words and thus
     * we cannot find pubkey in our hashmaps.
     */
    public DeterministicKey getAuthKeyFromIndexOrPubKey(byte[] pubkey, int index) {
        DeterministicKey key = (DeterministicKey) findKeyFromPubKey(pubkey);
        if (key == null) {
            if (index == -1)
                return null;
            List<ChildNumber> path = checkNotNull(freshAuthKey().getParent()).getPath();
            key = getKeyByPath(HDUtils.append(path, new ChildNumber(index)));
            if (!Arrays.equals(key.getPubKey(), pubkey))
                return null;
        }
        return key;
    }
}
