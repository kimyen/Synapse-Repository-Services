package org.sagebionetworks.repo.model.dao.semaphore;

/**
 * A very simple Semaphore enforced by a database.
 * 
 * @author jmhill
 *
 */
public interface SemaphoreDao {
	
	/**
	 * Each type of lock to acquire. This enumeration should be extended for
	 * each type of lock that should be supported.
	 *
	 */
	public enum LockType {
		UNSENT_MESSAGE_WORKER,
	};
	
	/**
	 * Attempt to acquire at lock of a given type.
	 * @param type - The type of the lock to acquire.
	 * @param timeoutMS - The maximum amount of time in MS that the lock will be held.
	 * If the lock is not release before this amount of time has elapsed, the lock will automatically be released
	 * and another process will be able to acquire the lock.
	 * @return A lock token will be returned if the caller successfully acquired the lock. It is the responsibility of the lock
	 * holder to release the lock when finished by calling {@link #releaseLock(LockType, String)} passing this token.
	 */
	public String attemptToAcquireLock(LockType type, long timeoutMS);
	
	/**
	 * When the process is finished it should release the lock. This method should only be called by a process
	 * that received the lock from {@link #attemptToAcquireLock(LockType, long)}
	 * @param type - The type of the lock to release.
	 * @param token - The lock token returned by {@link #attemptToAcquireLock(LockType, long)}
	 * @return
	 */
	public boolean releaseLock(LockType type, String token);

}
