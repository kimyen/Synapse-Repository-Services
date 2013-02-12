package org.sagebionetworks.evaluation.manager;

import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.QueryResults;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.web.NotFoundException;

public interface EvaluationManager {

	/**
	 * Create a new Synapse Evaluation
	 */
	public Evaluation createEvaluation(UserInfo userInfo, Evaluation comp)
			throws DatastoreException, InvalidModelException, NotFoundException;

	/**
	 * Get a Synapse Evaluation by its id
	 */
	public Evaluation getEvaluation(String id)
			throws DatastoreException, NotFoundException, UnauthorizedException;

	/**
	 * Get a collection of Evaluations, within a given range
	 */
	public QueryResults<Evaluation> getInRange(long limit, long offset) 
			throws DatastoreException, NotFoundException;

	/**
	 * Get the total number of Evaluations in the system
	 */
	public long getCount() throws DatastoreException, NotFoundException;

	/**
	 * Find a Evaluation, by name
	 */
	public Evaluation findEvaluation(String name)
			throws DatastoreException, NotFoundException, UnauthorizedException;

	/**
	 * Update a Synapse Evaluation.
	 */
	public Evaluation updateEvaluation(UserInfo userInfo, Evaluation comp)
			throws DatastoreException, NotFoundException,
			UnauthorizedException, InvalidModelException,
			ConflictingUpdateException;

	/**
	 * Delete a Synapse Evaluation.
	 */
	public void deleteEvaluation(UserInfo userInfo, String id)
			throws DatastoreException, NotFoundException, UnauthorizedException;

	/**
	 * Check whether a given user is an adminsitrator of a given Evaluation.
	 * 
	 * @param userId
	 * @param evalId
	 * @return
	 */
	public boolean isEvalAdmin(String userId, String evalId) throws DatastoreException, UnauthorizedException, NotFoundException;

	/**
	 * Update the eTag of a Evaluation. For use when modifying objects
	 * associated with a Evaluation for migration purposes.
	 * 
	 * Note that, besides the eTag change, this method performs a NOOP update.
	 * 
	 * @param evalId
	 * @throws NotFoundException
	 */
	void updateEvaluationEtag(String evalId) throws NotFoundException;
	
}