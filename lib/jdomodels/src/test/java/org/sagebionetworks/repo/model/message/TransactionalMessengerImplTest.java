package org.sagebionetworks.repo.model.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.dbo.dao.DBOChangeDAO;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.TestClock;
import org.sagebionetworks.util.ThreadLocalProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Unit (mocked) test for TransactionalMessengerImpl.
 * @author jmhill
 *
 */
public class TransactionalMessengerImplTest {
	
	DataSourceTransactionManager mockTxManager;
	DBOChangeDAO mockChangeDAO;
	TransactionSynchronizationProxy stubProxy;
	TransactionalMessengerObserver mockObserver;
	private TransactionalMessengerImpl messenger;
	private TestClock testClock = new TestClock();
	
	@Before
	public void before(){
		mockTxManager = Mockito.mock(DataSourceTransactionManager.class);
		mockChangeDAO = Mockito.mock(DBOChangeDAO.class);
		stubProxy = new TransactionSynchronizationProxyStub();
		mockObserver = Mockito.mock(TransactionalMessengerObserver.class);
		messenger = new TransactionalMessengerImpl(mockTxManager, mockChangeDAO, stubProxy, testClock);
		messenger.registerObserver(mockObserver);
		ThreadLocalProvider.getInstance(AuthorizationConstants.USER_ID_PARAM, Long.class).set(null);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testChangeMessageKeyNull(){
		new ChangeMessageKey(null);
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testChangeMessageKeyNullId(){
		new ChangeMessageKey(new ChangeMessage());
	}
	
	@Test (expected=IllegalArgumentException.class)
	public void testChangeMessageKeyNullType(){
		ChangeMessage message = new ChangeMessage();
		message.setObjectId("notNull");
		new ChangeMessageKey(message);
	}
	
	@Test
	public void testChangeMessageKeyEquals(){
		ChangeMessage message = new ChangeMessage();
		message.setObjectId("123");
		message.setObjectType(ObjectType.ENTITY);
		// Create the first key
		ChangeMessageKey one =  new ChangeMessageKey(message);
		// Create the second key
		message = new ChangeMessage();
		message.setObjectId("123");
		message.setObjectType(ObjectType.ENTITY);
		ChangeMessageKey two =  new ChangeMessageKey(message);
		assertEquals(one, two);
		// Third is not equals
		message = new ChangeMessage();
		message.setObjectId("456");
		message.setObjectType(ObjectType.ENTITY);
		ChangeMessageKey thrid =  new ChangeMessageKey(message);
		assertFalse(thrid.equals(two));
		assertFalse(two.equals(thrid));
		
		// fourth is not equals
		message = new ChangeMessage();
		message.setObjectId("123");
		message.setObjectType(ObjectType.ACTIVITY);
		ChangeMessageKey forth =  new ChangeMessageKey(message);
		assertFalse(forth.equals(two));
		assertFalse(two.equals(forth));
	}
	
	@Test
	public void testSendMessage(){
		ChangeMessage message = new ChangeMessage();
		message.setChangeNumber(new Long(123));
		message.setTimestamp(new Date(System.currentTimeMillis()/1000*1000));
		message.setObjectEtag("etag");
		message.setObjectId("syn456");
		message.setParentId("syn789");
		message.setObjectType(ObjectType.ENTITY);
		message.setChangeType(ChangeType.DELETE);
		// Send the message
		messenger.sendMessageAfterCommit(message);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());
		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		List<ChangeMessage> list = new ArrayList<ChangeMessage>();
		list.add(message);
		verify(mockChangeDAO, times(1)).replaceChange(list);
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// Verify that the one message was fired.
		verify(mockObserver, times(1)).fireChangeMessage(message);
		// It should only be called once total!
		verify(mockObserver, times(1)).fireChangeMessage(any(ChangeMessage.class));
	}
	
	@Test
	public void testSendMessageTwice() throws JSONObjectAdapterException{
		ChangeMessage first = new ChangeMessage();
		first.setChangeNumber(new Long(123));
		first.setTimestamp(new Date(System.currentTimeMillis()/1000*1000));
		first.setObjectEtag("etag");
		first.setObjectId("syn456");
		first.setParentId("syn789");
		first.setObjectType(ObjectType.ENTITY);
		first.setChangeType(ChangeType.DELETE);
		
		String json = EntityFactory.createJSONStringForEntity(first);
		ChangeMessage second = EntityFactory.createEntityFromJSONString(json, ChangeMessage.class);
		// Change the etag of the second
		second.setObjectEtag("etagtwo");
		
		// Send the message first message
		messenger.sendMessageAfterCommit(first);
		messenger.sendMessageAfterCommit(second);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());
		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		List<ChangeMessage> list = new ArrayList<ChangeMessage>();
		// The second should get sent but not the fist.
		list.add(second);
		verify(mockChangeDAO, times(1)).replaceChange(list);
		list = new ArrayList<ChangeMessage>();
		list.add(first);
		verify(mockChangeDAO, never()).replaceChange(list);
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// The second message should get sent but not the first
		verify(mockObserver, times(1)).fireChangeMessage(second);
		verify(mockObserver, never()).fireChangeMessage(first);
	}
	
	@Test
	public void testSendMessageSameIdDifferntType() throws JSONObjectAdapterException{
		ChangeMessage first = new ChangeMessage();
		first.setChangeNumber(new Long(123));
		first.setTimestamp(new Date(System.currentTimeMillis()/1000*1000));
		first.setObjectId("456");
		first.setObjectType(ObjectType.ACTIVITY);
		first.setChangeType(ChangeType.UPDATE);
		
		// This has the same id as the first but is of a different type.
		ChangeMessage second = new ChangeMessage();
		second.setChangeNumber(new Long(124));
		second.setTimestamp(new Date(System.currentTimeMillis()/1000*1000));
		second.setObjectId("456");
		second.setObjectType(ObjectType.PRINCIPAL);
		second.setChangeType(ChangeType.UPDATE);
		
		// Send the message first message
		messenger.sendMessageAfterCommit(first);
		messenger.sendMessageAfterCommit(second);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());
		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// The second message should get sent and so should the first
		verify(mockObserver, times(1)).fireChangeMessage(second);
		verify(mockObserver, times(1)).fireChangeMessage(first);
		verify(mockObserver, times(2)).fireChangeMessage(any(ChangeMessage.class));
	}

	/**
	 * PLFM-1662 was a bug the resulted in duplicate messages being broadcast. One of the messages did not have
	 * a change number or time stamp, while the other messages was correct.
	 */
	@Test
	public void testPLFM_1662(){
		mockTxManager = Mockito.mock(DataSourceTransactionManager.class);
		// We need stub dao to detect this bug.
		DBOChangeDAO stubChangeDao = new StubDBOChangeDAO();
		mockChangeDAO = Mockito.mock(DBOChangeDAO.class);
		stubProxy = new TransactionSynchronizationProxyStub();
		mockObserver = Mockito.mock(TransactionalMessengerObserver.class);
		messenger = new TransactionalMessengerImpl(mockTxManager, stubChangeDao, stubProxy, testClock);
		messenger.registerObserver(mockObserver);
		
		ChangeMessage message = new ChangeMessage();
		message.setChangeNumber(new Long(123));
		message.setTimestamp(new Date(System.currentTimeMillis()/1000*1000));
		message.setObjectEtag("etag");
		message.setObjectId("syn456");
		message.setParentId("syn789");
		message.setObjectType(ObjectType.ENTITY);
		message.setChangeType(ChangeType.DELETE);
		// Send the message
		messenger.sendMessageAfterCommit(message);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());
		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		List<ChangeMessage> list = new ArrayList<ChangeMessage>();
		list.add(message);
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// It should only be called once total!
		verify(mockObserver, times(1)).fireChangeMessage(any(ChangeMessage.class));
		
	}

	@Test(expected = IllegalArgumentException.class)
	public void testModificationMessageKeyNull() {
		new ModificationMessageKey(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testModificationMessageKeyNullId() {
		new ModificationMessageKey(new ModificationMessage());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testModificationMessageKeyNullUser() {
		ModificationMessage message = new ModificationMessage();
		message.setUserId(null);
		message.setProjectId(0L);
		message.setEntityId(null);
		new ModificationMessageKey(message);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testModificationMessageKeyDuplicateIds() {
		ModificationMessage message = new ModificationMessage();
		message.setUserId(0L);
		message.setProjectId(0L);
		message.setEntityId("0L");
		new ModificationMessageKey(message);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testModificationMessageKeyNoIds() {
		ModificationMessage message = new ModificationMessage();
		message.setUserId(0L);
		message.setProjectId(null);
		message.setEntityId(null);
		new ModificationMessageKey(message);
	}

	@Test
	public void testModificationMessageKeyEquals() {
		ModificationMessage message = new ModificationMessage();
		message.setProjectId(123L);
		message.setUserId(100L);
		message.setEntityId(null);
		// Create the first key
		ModificationMessageKey one = new ModificationMessageKey(message);
		// Create the second key
		message = new ModificationMessage();
		message.setProjectId(123L);
		message.setUserId(100L);
		message.setEntityId(null);
		ModificationMessageKey two = new ModificationMessageKey(message);
		assertEquals(one, two);
		// Third is not equals
		message = new ModificationMessage();
		message.setProjectId(456L);
		message.setUserId(100L);
		message.setEntityId(null);
		ModificationMessageKey thrid = new ModificationMessageKey(message);
		assertFalse(thrid.equals(two));
		assertFalse(two.equals(thrid));

		// fourth is not equals
		message = new ModificationMessage();
		message.setProjectId(456L);
		message.setUserId(200L);
		message.setEntityId(null);
		ModificationMessageKey forth = new ModificationMessageKey(message);
		assertFalse(forth.equals(two));
		assertFalse(two.equals(forth));
	}

	@Test
	public void testSendModificationMessageNothingHappensWithoutId() {
		// Send the message
		messenger.sendModificationMessageAfterCommit(123L, null);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(0, stubProxy.getSynchronizations().size());
	}

	@Test
	public void testSendModificationMessageNothingHappensWithAnonymous() {
		ThreadLocalProvider.getInstance(AuthorizationConstants.USER_ID_PARAM, Long.class).set(
				BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId());
		// Send the message
		messenger.sendModificationMessageAfterCommit(123L, null);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(0, stubProxy.getSynchronizations().size());
	}

	@Test
	public void testSendModificationMessage() {
		ThreadLocalProvider.getInstance(AuthorizationConstants.USER_ID_PARAM, Long.class).set(100L);
		// Send the message
		messenger.sendModificationMessageAfterCommit(123L, null);
		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());
		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		ModificationMessage message = new ModificationMessage();
		message.setProjectId(123L);
		message.setUserId(100L);
		message.setTimestamp(testClock.now());
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// Verify that the one message was fired.
		verify(mockObserver, times(1)).fireModificationMessage(message);
		// It should only be called once total!
		verify(mockObserver, times(1)).fireModificationMessage(any(ModificationMessage.class));
	}

	@Test
	public void testSendModificationMessageTwice() throws JSONObjectAdapterException {
		ThreadLocalProvider.getInstance(AuthorizationConstants.USER_ID_PARAM, Long.class).set(100L);

		ModificationMessage first = new ModificationMessage();
		first.setProjectId(123L);
		first.setUserId(100L);
		first.setTimestamp(testClock.now());

		// Send the message first message
		messenger.sendModificationMessageAfterCommit(123L, null);
		testClock.warpForward(1000);
		messenger.sendModificationMessageAfterCommit(123L, null);

		assertNotNull(stubProxy.getSynchronizations());
		assertEquals(1, stubProxy.getSynchronizations().size());

		ModificationMessage second = new ModificationMessage();
		second.setProjectId(123L);
		second.setUserId(100L);
		second.setTimestamp(testClock.now());

		// Simulate the before commit
		stubProxy.getSynchronizations().get(0).beforeCommit(true);
		// Simulate the after commit
		stubProxy.getSynchronizations().get(0).afterCommit();
		// The second message should get sent but not the first
		verify(mockObserver, times(1)).fireModificationMessage(second);
		verify(mockObserver, never()).fireModificationMessage(first);
	}
}
