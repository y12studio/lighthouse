package lighthouse.protocol;

import com.google.bitcoin.core.*;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.params.UnitTestParams;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.script.ScriptOpCodes;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import lighthouse.wallet.PledgingWallet;
import org.junit.Before;
import org.junit.Test;

import java.security.SignatureException;
import java.util.List;

import static com.google.bitcoin.testing.FakeTxBuilder.createFakeTx;
import static com.google.bitcoin.testing.FakeTxBuilder.roundTripTransaction;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static lighthouse.protocol.LHUtils.checkedGet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ProjectTest {
    private List<TransactionOutput> EMPTY_LIST = ImmutableList.of();

    private Address toAddress;
    private LHProtos.ProjectDetails.Builder details;
    private NetworkParameters params = UnitTestParams.get();
    private LHProtos.Project.Builder projectBuilder;
    private PledgingWallet wallet;

    private static class TxData {
        Transaction pledge;
        Transaction fakeStub;
    }

    private TxData makePledge(LHProtos.ProjectDetails.Builder details, double percentage) {
        TxData txData = new TxData();
        txData.pledge = new Transaction(params);
        long total = 0;
        for (LHProtos.Output output : details.getOutputsList()) {
            txData.pledge.addOutput(Coin.valueOf(output.getAmount()), new Script(output.getScript().toByteArray()));
            total += output.getAmount();
        }
        ECKey key = new ECKey();
        final long pledgeSatoshis = (long) (total * percentage);
        txData.fakeStub = createFakeTx(params, Coin.valueOf(pledgeSatoshis), key.toAddress(params));
        txData.pledge.addSignedInput(new TransactionOutPoint(params, 0, txData.fakeStub),
                txData.fakeStub.getOutput(0).getScriptPubKey(), key, Transaction.SigHash.ALL, true);
        txData.pledge = roundTripTransaction(params, txData.pledge);
        return txData;
    }

    @Before
    public void setUp() throws Exception {
        BriefLogFormatter.init();
        // Some constant key to make tests faster and deterministic.
        wallet = new PledgingWallet(params);
        toAddress = wallet.freshReceiveAddress();
        details = Project.makeDetails(
                "My cool project", "A project to make awesome things ... out of Lego!",
                toAddress, Coin.COIN, wallet.freshAuthKey(), wallet.getKeychainLookaheadSize());
        projectBuilder = LHProtos.Project.newBuilder();
        projectBuilder.setSerializedPaymentDetails(details.build().toByteString());
    }

    @Test
    public void accessors() throws Exception {
        Project project = new Project(projectBuilder.build());
        assertEquals("My cool project", project.getTitle());
        assertEquals("A project to make awesome things ... out of Lego!", project.getMemo());
        assertEquals(Coin.COIN, project.getGoalAmount());
    }

    @Test(expected = Ex.NoTransactionData.class)
    public void badPledgeNoTx() throws Exception {
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.setTotalInputValue(0);
        pledge.setProjectId("abc");
        pledge.setTimestamp(Utils.currentTimeSeconds());
        Project project = new Project(projectBuilder.build());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(EMPTY_LIST), pledge.build()));
    }

    @Test(expected = Ex.TxWrongNumberOfOutputs.class)
    public void badPledgeInsufficientOutputs() throws Exception {
        TxData pledgeTX = makePledge(details, 0.5);
        // Add an output to the contract and verify the pledge fails with the right exception.
        details.addOutputsBuilder().setScript(details.getOutputs(0).getScript()).setAmount(100);
        projectBuilder.setSerializedPaymentDetails(details.build().toByteString());
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue(0);
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(EMPTY_LIST), pledge.build()));
    }

    @Test(expected = Ex.OutputMismatch.class)
    public void badPledgeWrongOutput() throws Exception {
        TxData pledgeTX = makePledge(details, 0.5);
        pledgeTX.pledge.getOutput(0).setValue(Coin.valueOf(100));
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue(0);
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(EMPTY_LIST), pledge.build()));
    }

    @Test(expected = Ex.UnknownUTXO.class)
    public void missingUTXO() throws Exception {
        // Check the exception thrown if the pledge seems to be double spent away or is just invalid.
        TxData pledgeTX = makePledge(details, 0.5);
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue(0);
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(EMPTY_LIST), pledge.build()));
    }

    @Test(expected = ScriptException.class)
    public void badSignature() throws Exception {
        TxData pledgeTX = makePledge(details, 0.1);
        pledgeTX.pledge.getInput(0).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue(0);
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        List<TransactionOutput> outputs = ImmutableList.of(pledgeTX.fakeStub.getOutput(0).duplicateDetached());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(outputs), pledge.build()));
    }

    @Test(expected = VerificationException.class)
    public void badOutputScript() throws Exception {
        TxData pledgeTX = makePledge(details, 0.1);
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        final Coin val = Coin.COIN.divide(10);
        pledge.setTotalInputValue(val.value);
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        TransactionOutput badOutput = new TransactionOutput(params, null, val,
                new ScriptBuilder().op(ScriptOpCodes.OP_TRUE).build().getProgram());
        List<TransactionOutput> outputs = ImmutableList.of(badOutput);
        checkedGet(project.verifyPledge(outPoints -> completedFuture(outputs), pledge.build()));
    }

    @Test(expected = Ex.CachedValueMismatch.class)
    public void badTotalValueField() throws Exception {
        // We require the pledge to (redundantly) specify the total value of all inputs, even though we check it
        // and calculate it by requesting the UTXOs, because that way once checked we can just save the pledge
        // message in a way that says it was validated and not have to look up UTXOs again in future, or wrap it
        // with extra data.
        TxData pledgeTX = makePledge(details, 0.1);
        LHProtos.Pledge.Builder pledge = pledgeToBuilder(pledgeTX);
        Project project = new Project(projectBuilder.build());
        List<TransactionOutput> outputs = ImmutableList.of(pledgeTX.fakeStub.getOutput(0).duplicateDetached());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(outputs), pledge.build()));
    }

    private LHProtos.Pledge.Builder pledgeToBuilder(TxData pledgeTX) {
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue((long) (Coin.COIN.longValue() * 0.2));  // Mismatch.
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        return pledge;
    }

    @Test(expected = VerificationException.DuplicatedOutPoint.class)
    public void duplicatedOutpoints() throws Exception {
        // Verify that the pledge we've been given doesn't contain multiple inputs that connect to the same output.
        // This would represent a malicious attempt to make us think we received more pledges than we really did.
        TxData pledgeTX = makePledge(details, 0.1);
        pledgeTX.pledge.addInput(pledgeTX.pledge.getInput(0).duplicateDetached());
        Project project = new Project(projectBuilder.build());
        project.fastSanityCheck(pledgeToBuilder(pledgeTX).build());
    }

    @Test
    public void nonStandardInput() throws Exception {
        // TODO: Check for pledges that'd make our claim non-standard and reject.
    }

    @Test
    public void okPledge() throws Exception {
        TxData pledgeTX = makePledge(details, 0.1);
        LHProtos.Pledge.Builder pledge = LHProtos.Pledge.newBuilder();
        pledge.addTransactions(ByteString.copyFrom(pledgeTX.pledge.bitcoinSerialize()));
        pledge.setTotalInputValue((long) (Coin.COIN.longValue() * 0.1));
        pledge.setTimestamp(Utils.currentTimeSeconds());
        pledge.setProjectId("abc");
        Project project = new Project(projectBuilder.build());
        List<TransactionOutput> outputs = ImmutableList.of(pledgeTX.fakeStub.getOutput(0).duplicateDetached());
        checkedGet(project.verifyPledge(outPoints -> completedFuture(outputs), pledge.build()));
    }

    @Test(expected = Ex.ValueMismatch.class)
    public void incompleteContract() throws Exception {
        TxData pledgeTX1 = makePledge(details, 0.1);
        TxData pledgeTX2 = makePledge(details, 0.7);
        LHProtos.Pledge.Builder pledge1 = LHProtos.Pledge.newBuilder();
        pledge1.addTransactions(ByteString.copyFrom(pledgeTX1.pledge.bitcoinSerialize()));
        pledge1.setTotalInputValue((long) (Coin.COIN.longValue() * 0.1));
        pledge1.setTimestamp(Utils.currentTimeSeconds());
        pledge1.setProjectId("abc");
        LHProtos.Pledge.Builder pledge2 = LHProtos.Pledge.newBuilder();
        pledge2.addTransactions(ByteString.copyFrom(pledgeTX2.pledge.bitcoinSerialize()));
        pledge2.setTotalInputValue((long) (Coin.COIN.longValue() * 0.7));
        pledge2.setTimestamp(Utils.currentTimeSeconds());
        pledge2.setProjectId("abc");

        Project project = new Project(projectBuilder.build());
        Transaction contract = project.completeContract(ImmutableSet.of(pledge1.build(), pledge2.build()));
        assertEquals(2, contract.getInputs().size());
    }

    @Test
    public void completeContract() throws Exception {
        TxData pledgeTX1 = makePledge(details, 0.1);
        TxData pledgeTX2 = makePledge(details, 0.9);
        LHProtos.Pledge.Builder pledge1 = LHProtos.Pledge.newBuilder();
        pledge1.addTransactions(ByteString.copyFrom(pledgeTX1.pledge.bitcoinSerialize()));
        pledge1.setTotalInputValue((long) (Coin.COIN.longValue() * 0.1));
        pledge1.setTimestamp(Utils.currentTimeSeconds());
        pledge1.setProjectId("abc");
        LHProtos.Pledge.Builder pledge2 = LHProtos.Pledge.newBuilder();
        pledge2.addTransactions(ByteString.copyFrom(pledgeTX2.pledge.bitcoinSerialize()));
        pledge2.setTotalInputValue((long) (Coin.COIN.longValue() * 0.9));
        pledge2.setTimestamp(Utils.currentTimeSeconds());
        pledge2.setProjectId("abc");

        Project project = new Project(projectBuilder.build());
        Transaction contract = project.completeContract(ImmutableSet.of(pledge1.build(), pledge2.build()));
        assertEquals(2, contract.getInputs().size());
    }

    @Test
    public void urlScrubber() throws Exception {
        assertEquals("a-really-cool-20-title-with-lots-asdf-of-weird--chars", LHUtils.titleToUrlString("A really $cool %20 Title with ;;lots asdf\n of weird // chars"));
    }

    @Test
    public void authKeys() throws Exception {
        details = Project.makeDetails(
                "My cool project", "A project to make awesome things ... out of Lego!",
                toAddress, Coin.COIN, wallet.freshAuthKey(), wallet.getKeychainLookaheadSize());
        Project project = new Project(details.build());
        String signature = project.signAsOwner(wallet, "legolegolego", null);
        project.authenticateOwner("legolegolego", signature);
        try {
            project.authenticateOwner("duplo!duplo!duplo!", signature);
            fail();
        } catch (SignatureException e) {
            // Expected.
        }
    }
}
