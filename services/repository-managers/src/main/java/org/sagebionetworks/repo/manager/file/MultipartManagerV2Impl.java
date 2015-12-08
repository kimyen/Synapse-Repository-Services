package org.sagebionetworks.repo.manager.file;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.apache.commons.codec.binary.Hex;
import org.sagebionetworks.repo.manager.ProjectSettingsManager;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.CompositeMultipartUploadStatus;
import org.sagebionetworks.repo.model.dbo.file.CreateMultipartRequest;
import org.sagebionetworks.repo.model.dbo.file.MultipartUploadDAO;
import org.sagebionetworks.repo.model.file.BatchPresignedUploadUrlRequest;
import org.sagebionetworks.repo.model.file.BatchPresignedUploadUrlResponse;
import org.sagebionetworks.repo.model.file.MultipartUploadRequest;
import org.sagebionetworks.repo.model.file.MultipartUploadStatus;
import org.sagebionetworks.repo.model.project.StorageLocationSetting;
import org.sagebionetworks.repo.transactions.WriteTransactionReadCommitted;
import org.sagebionetworks.schema.adapter.JSONEntity;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.upload.multipart.S3MultipartUploadDAO;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

public class MultipartManagerV2Impl implements MultipartManagerV2 {

	public static final long MAX_NUMBER_OF_PARTS = 10 * 1000; // 10K
	public static final long MIN_PART_SIZE_BYTES = 5 * 1024 * 1024; // 5MB

	@Autowired
	S3MultipartUploadDAO s3multipartUploadDAO;

	@Autowired
	MultipartUploadDAO multipartUploadDAO;

	@Autowired
	ProjectSettingsManager projectSettingsManager;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.repo.manager.file.MultipartManagerV2#
	 * startOrResumeMultipartUpload(org.sagebionetworks.repo.model.UserInfo,
	 * org.sagebionetworks.repo.model.file.MultipartUploadRequest,
	 * java.lang.Boolean)
	 */
	@WriteTransactionReadCommitted
	@Override
	public MultipartUploadStatus startOrResumeMultipartUpload(UserInfo user,
			MultipartUploadRequest request, Boolean forceRestart) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(user.getId(), "UserInfo.getId");
		ValidateArgument.required(request, "MultipartUploadRequest");
		ValidateArgument.required(request.getFileName(),
				"MultipartUploadRequest.fileName");
		ValidateArgument.required(request.getFileSizeBytes(),
				"MultipartUploadRequest.fileSizeBytes");
		ValidateArgument.required(request.getPartSizeBytes(),
				"MultipartUploadRequest.PartSizeBytes");
		ValidateArgument.required(request.getContentMD5Hex(),
				"MultipartUploadRequest.MD5Hex");

		// anonymous cannot upload. See: PLFM-2621.
		if (AuthorizationUtils.isUserAnonymous(user)) {
			throw new UnauthorizedException("Anonymous cannot upload files.");
		}
		// The MD5 is used to identify if this upload request already exists for
		// this user.
		String requestMD5Hex = calculateMD5AsHex(request);
		// Clear all data if this is a forced restart
		if (forceRestart != null && forceRestart.booleanValue()) {
			multipartUploadDAO.deleteUploadStatus(user.getId(), requestMD5Hex);
		}

		// Has an upload already been started for this user and request
		CompositeMultipartUploadStatus status = multipartUploadDAO
				.getUploadStatus(user.getId(), requestMD5Hex);
		if (status == null) {
			// Since the status for this file does not exist, create it.
			status = createNewMultipartUpload(user, request, requestMD5Hex);
		}
		// Calculate the parts state for this file.
		String partsState = setupPartState(status);
		status.getMultipartUploadStatus().setPartsState(partsState);
		return status.getMultipartUploadStatus();
	}

	/**
	 * Create a new multi-part upload both in S3 and the database.
	 * 
	 * @param user
	 * @param request
	 * @param requestMD5Hex
	 * @return
	 */
	private CompositeMultipartUploadStatus createNewMultipartUpload(
			UserInfo user, MultipartUploadRequest request, String requestMD5Hex) {
		// This is the first time we have seen this request.
		StorageLocationSetting locationSettings = getStorageLocationSettions(request
				.getStorageLocationId());
		// the bucket depends on the upload location used.
		String bucket = MultipartUtils.getBucket(locationSettings);
		// create a new key for this file.
		String key = MultipartUtils.createNewKey(user.getId().toString(),
				request.getFileName(), locationSettings);
		String uploadToken = s3multipartUploadDAO.initiateMultipartUpload(
				bucket, key, request);
		String requestJson = createRequestJSON(request);
		// How many parts will be needed to upload this file?
		int numberParts = calculateNumberOfParts(request.getFileSizeBytes(),
				request.getPartSizeBytes());
		// Start the upload
		return multipartUploadDAO
				.createUploadStatus(new CreateMultipartRequest(user.getId(),
						requestMD5Hex, requestJson, uploadToken, bucket, key,
						numberParts));
	}

	/**
	 * Setup the partsState string for a given status multi-part status. For the
	 * cases where the file upload is complete, this string is built without a
	 * database call.
	 * 
	 * @param status
	 */
	public String setupPartState(CompositeMultipartUploadStatus status) {
		if (status.getMultipartUploadStatus().getResultFileHandleId() != null) {
			// When the upload is done we just create a string of all '1'
			// without hitting the DB.
			return getCompletePartStateString(status.getNumberOfParts());
		} else {
			// Get the parts state from the DAO.
			return multipartUploadDAO.getPartsState(status
					.getMultipartUploadStatus().getUploadId(), status
					.getNumberOfParts());
		}
	}

	/**
	 * Create string of '1' of the size of the passed number of parts.
	 * 
	 * @param numberOfParts
	 * @return
	 */
	public static String getCompletePartStateString(int numberOfParts) {
		char[] chars = new char[numberOfParts];
		Arrays.fill(chars, '1');
		return new String(chars);
	}

	/**
	 * Calculate the number of parts required to upload file given a part size
	 * and file size.
	 * 
	 * @param fileSize
	 * @param partSize
	 * @return
	 */
	public static int calculateNumberOfParts(long fileSize, long partSize) {
		if (fileSize < 1) {
			throw new IllegalArgumentException(
					"File size must be at least one byte");
		}
		if (partSize < MIN_PART_SIZE_BYTES) {
			throw new IllegalArgumentException("Part size of " + partSize
					+ " bytes is too small.  The minimum part size is :"
					+ MIN_PART_SIZE_BYTES + " bytes");
		}
		// Only one part is needed when the file is smaller than the part size.
		if (partSize > fileSize) {
			return 1;
		}
		int remainder = (int) (fileSize % partSize);
		int numberOfParts = ((int) (fileSize / partSize))
				+ (remainder > 0 ? 1 : 0);
		// Validate the number of parts.
		if (numberOfParts > MAX_NUMBER_OF_PARTS) {
			throw new IllegalArgumentException(
					"File Upload would required: "
							+ numberOfParts
							+ " parts, which exceeds the maximum number of allowed parts of: "
							+ MAX_NUMBER_OF_PARTS
							+ ". Please choose a larger part size");
		}
		return numberOfParts;
	}

	/**
	 * Get the JSON string of the request.
	 * 
	 * @param request
	 * @return
	 */
	public static String createRequestJSON(MultipartUploadRequest request) {
		try {
			return EntityFactory.createJSONStringForEntity(request);
		} catch (JSONObjectAdapterException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private StorageLocationSetting getStorageLocationSettions(Long storageId) {
		if (storageId == null) {
			return null;
		}
		return projectSettingsManager.getStorageLocationSetting(storageId);
	}

	/**
	 * Calculate the MD5 of the given
	 * 
	 * @param request
	 * @return
	 */
	public static String calculateMD5AsHex(JSONEntity request) {
		try {
			String json = EntityFactory.createJSONStringForEntity(request);
			byte[] jsonBytes = json.getBytes("UTF-8");
			MessageDigest messageDigest = MessageDigest.getInstance("MD5");
			byte[] md5Byptes = messageDigest.digest(jsonBytes);
			return new String(Hex.encodeHex(md5Byptes));
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.repo.manager.file.MultipartManagerV2#
	 * getBatchPresignedUploadUrls(org.sagebionetworks.repo.model.UserInfo,
	 * org.sagebionetworks.repo.model.file.BatchPresignedUploadUrlRequest)
	 */
	@Override
	public BatchPresignedUploadUrlResponse getBatchPresignedUploadUrls(
			UserInfo user, BatchPresignedUploadUrlRequest request) {

		ValidateArgument.required(request, "BatchPresignedUploadUrlRequest");
		ValidateArgument.required(request.getPartNumbers(),
				"BatchPresignedUploadUrlRequest.partNumbers");
		if (request.getPartNumbers().isEmpty()) {
			throw new IllegalArgumentException(
					"BatchPresignedUploadUrlRequest.partNumbers must contain at least one value");
		}
		// lookup this upload.
		CompositeMultipartUploadStatus status = multipartUploadDAO.getUploadStatus(request.getUploadId());
		// validate the caller is the user that started this upload.
		validateStartedBy(user, status);
		return null;
	}

	/**
	 * Validate the caller started the given 
	 * @param user
	 * @param startedBy
	 */
	private void validateStartedBy(UserInfo user, CompositeMultipartUploadStatus composite) {
		ValidateArgument.required(user, "UserInfo");
		ValidateArgument.required(composite, "CompositeMultipartUploadStatus");
		ValidateArgument.required(composite.getMultipartUploadStatus(), "CompositeMultipartUploadStatus.multipartUploadStatus");
		ValidateArgument.required(composite.getMultipartUploadStatus().getStartedBy(), "CompositeMultipartUploadStatus.multipartUploadStatus.startedBy");
		Long startedBy = Long.parseLong(composite.getMultipartUploadStatus().getStartedBy());
		if (!startedBy.equals(user.getId())) {
			throw new UnauthorizedException(
					"Only the user that started a multipart upload can get part upload pre-signed URLs for that file upload.");
		}
	}

}
