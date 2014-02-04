package org.hive2hive.core.test.process.common.massages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import net.tomp2p.futures.FutureGet;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.ObjectDataReply;

import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.exceptions.SendFailedException;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.network.messages.AcceptanceReply;
import org.hive2hive.core.network.messages.direct.response.ResponseMessage;
import org.hive2hive.core.process.Process;
import org.hive2hive.core.process.common.messages.BaseMessageProcessStep;
import org.hive2hive.core.test.H2HJUnitTest;
import org.hive2hive.core.test.H2HTestData;
import org.hive2hive.core.test.H2HWaiter;
import org.hive2hive.core.test.network.NetworkTestUtil;
import org.hive2hive.core.test.network.messages.TestMessage;
import org.hive2hive.core.test.network.messages.TestMessageWithReply;
import org.hive2hive.core.test.process.ProcessTestUtil;
import org.hive2hive.core.test.process.TestProcessListener;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the {@link BaseMessageProcessStep} class. Checks if the process step successes when message
 * successfully arrives and if the process step fails (triggers rollback) when the sending of a message fails.
 * 
 * @author Seppi
 */
public class BaseMessageProcessStepTest extends H2HJUnitTest {

	private static List<NetworkManager> network;
	private static final int networkSize = 10;
	private static Random random = new Random();

	@BeforeClass
	public static void initTest() throws Exception {
		testClass = BaseMessageProcessStepTest.class;
		beforeClass();
		network = NetworkTestUtil.createNetwork(networkSize);
		NetworkTestUtil.createSameKeyPair(network);
	}

	/**
	 * Sends an asynchronous message through a process step. This test checks if the process step successes
	 * when the message arrives at the right target node (node which is responsible for the given key). This
	 * is verified by locally storing and looking for the sent test data at the receiving node.
	 * 
	 * @throws IOException
	 * @throws ClassNotFoundException
	 * @throws NoPeerConnectionException
	 */
	@Test
	public void baseMessageProcessStepTestOnSuccess() throws ClassNotFoundException, IOException,
			NoPeerConnectionException {
		// select two random nodes
		NetworkManager nodeA = network.get(random.nextInt(networkSize / 2));
		final NetworkManager nodeB = network.get(random.nextInt(networkSize / 2) + networkSize / 2);
		// generate random data and content key
		String data = NetworkTestUtil.randomString();
		String contentKey = NetworkTestUtil.randomString();

		Number160 lKey = Number160.createHash(nodeB.getNodeId());
		Number160 dKey = Number160.ZERO;
		Number160 cKey = Number160.createHash(contentKey);

		// check if selected location is empty
		FutureGet futureGet = nodeA.getDataManager().get(lKey, dKey, cKey);
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());

		// create a message with target node B
		final TestMessage message = new TestMessage(nodeB.getNodeId(), contentKey, new H2HTestData(data));

		// initialize the process and the one and only step to test
		Process process = new Process(nodeA) {
		};

		BaseMessageProcessStep step = new BaseMessageProcessStep() {
			@Override
			public void handleResponseMessage(ResponseMessage responseMessage) {
				Assert.fail("Should be not used.");
			}

			@Override
			public void start() {
				try {
					send(message, nodeB.getPublicKey());
					getProcess().setNextStep(null);
				} catch (SendFailedException e) {
					Assert.fail();
				}
			}
		};
		process.setNextStep(step);
		TestProcessListener listener = new TestProcessListener();
		process.addListener(listener);
		process.start();

		// wait for the process to finish
		ProcessTestUtil.waitTillSucceded(listener, 100);

		// wait till message gets handled
		H2HWaiter w = new H2HWaiter(10);
		do {
			w.tickASecond();
			futureGet = nodeA.getDataManager().get(lKey, dKey, cKey);
			futureGet.awaitUninterruptibly();
		} while (futureGet.getData() == null);

		// verify that data arrived
		String result = ((H2HTestData) futureGet.getData().object()).getTestString();
		assertEquals(data, result);
	}

	/**
	 * Sends an asynchronous message through a process step. This test checks if the process step fails
	 * when the message gets denied at the target node (node which is responsible for the given key).
	 * 
	 * @throws NoPeerConnectionException
	 */
	@Test
	public void baseMessageProcessStepTestOnFailure() throws NoPeerConnectionException {
		// select two random nodes
		NetworkManager nodeA = network.get(random.nextInt(networkSize / 2));
		final NetworkManager nodeB = network.get(random.nextInt(networkSize / 2) + networkSize / 2);
		// generate random data and content key
		String data = NetworkTestUtil.randomString();
		String contentKey = NetworkTestUtil.randomString();

		Number160 lKey = Number160.createHash(nodeB.getNodeId());
		Number160 dKey = Number160.ZERO;
		Number160 cKey = Number160.createHash(contentKey);

		// check if selected location is empty
		FutureGet futureGet = nodeA.getDataManager().get(lKey, dKey, cKey);
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());

		// assign a denying message handler at target node
		nodeB.getConnection().getPeer().setObjectDataReply(new DenyingMessageReplyHandler());

		// create a message with target node B
		final TestMessage message = new TestMessage(nodeB.getNodeId(), contentKey, new H2HTestData(data));

		// initialize the process and the one and only step to test
		Process process = new Process(nodeA) {
		};

		BaseMessageProcessStep step = new BaseMessageProcessStep() {
			@Override
			public void handleResponseMessage(ResponseMessage responseMessage) {
				Assert.fail("Should be not used.");
			}

			@Override
			public void start() {
				try {
					send(message, nodeB.getPublicKey());
					getProcess().setNextStep(null);
				} catch (SendFailedException e) {
					getProcess().stop(e);
				}
			}
		};
		process.setNextStep(step);
		TestProcessListener listener = new TestProcessListener();
		process.addListener(listener);
		process.start();

		// wait for the process to finish
		ProcessTestUtil.waitTillFailed(listener, 10);

		// check if selected location is still empty
		futureGet = nodeA.getDataManager().get(lKey, dKey, cKey);
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());
	}

	/**
	 * Sends an asynchronous request message through a process step. This test checks if the process step
	 * successes when receiving node responds to a request message.
	 * 
	 * @throws IOException
	 * @throws ClassNotFoundException
	 * @throws NoPeerConnectionException
	 */
	@Test
	public void baseMessageProcessStepTestWithARequestMessage() throws ClassNotFoundException, IOException,
			NoPeerConnectionException {
		// select two random nodes
		final NetworkManager nodeA = network.get(random.nextInt(networkSize / 2));
		final NetworkManager nodeB = network.get(random.nextInt(networkSize / 2) + networkSize / 2);
		// generate a random content key
		final String contentKey = NetworkTestUtil.randomString();
		final Number160 lKeyA = Number160.createHash(nodeA.getNodeId());
		final Number160 lKeyB = Number160.createHash(nodeB.getNodeId());
		final Number160 dKey = Number160.ZERO;
		final Number160 cKey = Number160.createHash(contentKey);

		// check if selected locations are empty
		FutureGet futureGet = nodeA.getDataManager().get(lKeyB, dKey, cKey);
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());
		futureGet = nodeB.getDataManager().get(lKeyA, dKey, cKey);
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());

		// create a message with target node B
		final TestMessageWithReply message = new TestMessageWithReply(nodeB.getNodeId(), contentKey);

		// initialize the process and the one and only step to test
		Process process = new Process(nodeA) {
		};

		BaseMessageProcessStep step = new BaseMessageProcessStep() {
			@Override
			public void handleResponseMessage(ResponseMessage responseMessage) {
				// locally store on requesting node received data
				String receivedSecret = (String) responseMessage.getContent();
				try {
					nodeA.getDataManager().put(lKeyA, dKey, cKey, new H2HTestData(receivedSecret), null)
							.awaitUninterruptibly();
				} catch (NoPeerConnectionException e) {
					Assert.fail();
				}

				// step finished go further
				getProcess().setNextStep(null);
			}

			@Override
			public void start() {
				try {
					send(message, nodeB.getPublicKey());
				} catch (SendFailedException e) {
					Assert.fail();
				}
			}
		};
		process.setNextStep(step);
		TestProcessListener listener = new TestProcessListener();
		process.addListener(listener);
		process.start();

		// wait for the process to finish
		ProcessTestUtil.waitTillSucceded(listener, 10);

		// wait till response message gets handled
		H2HWaiter waiter = new H2HWaiter(10);
		do {
			waiter.tickASecond();
			futureGet = nodeA.getDataManager().get(lKeyA, dKey, cKey);
			futureGet.awaitUninterruptibly();
		} while (futureGet.getData() == null);

		// load and verify if same secret was shared
		String receivedSecret = ((H2HTestData) futureGet.getData().object()).getTestString();
		futureGet = nodeA.getDataManager().get(lKeyB, dKey, cKey);
		futureGet.awaitUninterruptibly();
		String originalSecret = ((H2HTestData) futureGet.getData().object()).getTestString();

		assertEquals(originalSecret, receivedSecret);
	}

	@AfterClass
	public static void endTest() {
		NetworkTestUtil.shutdownNetwork(network);
		afterClass();
	}

	private class DenyingMessageReplyHandler implements ObjectDataReply {
		@Override
		public Object reply(PeerAddress sender, Object request) throws Exception {
			return AcceptanceReply.FAILURE;
		}
	}
}
