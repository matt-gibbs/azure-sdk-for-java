/**
 * Copyright Microsoft Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.windowsazure.services.core.storage.utils.implementation;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.Date;
import java.util.concurrent.TimeoutException;

import javax.xml.stream.XMLStreamException;

import com.microsoft.windowsazure.services.core.storage.OperationContext;
import com.microsoft.windowsazure.services.core.storage.RequestResult;
import com.microsoft.windowsazure.services.core.storage.ResponseReceivedEvent;
import com.microsoft.windowsazure.services.core.storage.RetryNoRetry;
import com.microsoft.windowsazure.services.core.storage.RetryPolicy;
import com.microsoft.windowsazure.services.core.storage.RetryPolicyFactory;
import com.microsoft.windowsazure.services.core.storage.RetryResult;
import com.microsoft.windowsazure.services.core.storage.SendingRequestEvent;
import com.microsoft.windowsazure.services.core.storage.StorageErrorCode;
import com.microsoft.windowsazure.services.core.storage.StorageErrorCodeStrings;
import com.microsoft.windowsazure.services.core.storage.StorageException;
import com.microsoft.windowsazure.services.core.storage.utils.Utility;
import com.microsoft.windowsazure.services.table.client.TableServiceException;

/**
 * RESERVED FOR INTERNAL USE. A class that handles execution of StorageOperations and enforces retry policies.
 */
public final class ExecutionEngine {

    /**
     * Executes an operation without a retry policy.
     * 
     * @param <CLIENT_TYPE>
     *            The service client type
     * @param <PARENT_TYPE>
     *            The type of the parent object, i.e. CloudBlobContainer for downloadAttributes etc.
     * @param <RESULT_TYPE>
     *            The type of the expected result
     * @param client
     *            the service client associated with the request
     * @param parentObject
     *            the parent object
     * @param task
     *            the StorageOperation to execute
     * @param opContext
     *            an object used to track the execution of the operation
     * @return the result of the operation
     * @throws StorageException
     *             an exception representing any error which occurred during the operation.
     */
    protected static <CLIENT_TYPE, PARENT_TYPE, RESULT_TYPE> RESULT_TYPE execute(final CLIENT_TYPE client,
            final PARENT_TYPE parentObject, final StorageOperation<CLIENT_TYPE, PARENT_TYPE, RESULT_TYPE> task,
            final OperationContext opContext) throws StorageException {
        return executeWithRetry(client, parentObject, task, RetryNoRetry.getInstance(), opContext);
    }

    /**
     * Executes an operation and enforces a retrypolicy to handle any potential errors
     * 
     * @param <CLIENT_TYPE>
     *            The service client type
     * @param <PARENT_TYPE>
     *            The type of the parent object, i.e. CloudBlobContainer for downloadAttributes etc.
     * @param <RESULT_TYPE>
     *            The type of the expected result
     * @param client
     *            the service client associated with the request
     * @param parentObject
     *            the parent object
     * @param task
     *            the StorageOperation to execute
     * @param policyFactory
     *            the factory used to generate a new retry policy instance
     * @param opContext
     *            an object used to track the execution of the operation
     * @return the result of the operation
     * @throws StorageException
     *             an exception representing any error which occurred during the operation.
     */
    public static <CLIENT_TYPE, PARENT_TYPE, RESULT_TYPE> RESULT_TYPE executeWithRetry(final CLIENT_TYPE client,
            final PARENT_TYPE parentObject, final StorageOperation<CLIENT_TYPE, PARENT_TYPE, RESULT_TYPE> task,
            final RetryPolicyFactory policyFactory, final OperationContext opContext) throws StorageException {

        final RetryPolicy policy = policyFactory.createInstance(opContext);
        RetryResult retryRes;
        int currentRetryCount = 0;
        StorageException translatedException = null;
        final long startTime = new Date().getTime();

        while (true) {
            try {
                // reset result flags
                task.initialize(opContext);

                final RESULT_TYPE result = task.execute(client, parentObject, opContext);

                opContext.setClientTimeInMs(new Date().getTime() - startTime);

                if (!task.isNonExceptionedRetryableFailure()) {
                    // Success return result and drain the input stream.
                    HttpURLConnection request = task.getConnection();
                    if ((task.getResult().getStatusCode() >= 200) && (task.getResult().getStatusCode() < 300)) {
                        if (request != null) {
                            InputStream inStream = request.getInputStream();
                            try {
                                Utility.writeToOutputStream(inStream, null, -1, false, false, task.getResult(), null);
                            }
                            // At this point, we already have a result / exception to return to the user.
                            // This is just an optimization to improve socket reuse.
                            catch (final IOException ex) {
                            }
                            catch (StorageException e) {
                            }
                            finally {
                                inStream.close();
                            }
                        }
                    }
                    return result;
                }
                else {
                    // The task may have already parsed an exception.
                    translatedException = task.materializeException(getLastRequestObject(opContext), opContext);
                    task.getResult().setException(translatedException);

                    // throw on non retryable status codes: 501, 505, blob type
                    // mismatch
                    if (task.getResult().getStatusCode() == HttpURLConnection.HTTP_NOT_IMPLEMENTED
                            || task.getResult().getStatusCode() == HttpURLConnection.HTTP_VERSION
                            || translatedException.getErrorCode().equals(StorageErrorCodeStrings.INVALID_BLOB_TYPE)) {
                        throw translatedException;
                    }
                }
            }
            catch (final TimeoutException e) {
                // Retryable
                translatedException = StorageException
                        .translateException(getLastRequestObject(opContext), e, opContext);
                task.getResult().setException(translatedException);
            }
            catch (final SocketTimeoutException e) {
                // Retryable
                translatedException = new StorageException(StorageErrorCodeStrings.OPERATION_TIMED_OUT,
                        "The operation did not complete in the specified time.", -1, null, e);
                task.getResult().setException(translatedException);
            }
            catch (final IOException e) {
                // Retryable
                translatedException = StorageException
                        .translateException(getLastRequestObject(opContext), e, opContext);
                task.getResult().setException(translatedException);
            }
            catch (final XMLStreamException e) {
                // Non Retryable except when the inner exception is actually an IOException

                // Only in the case of xml exceptions that are due to connection issues.
                if (e.getCause() instanceof SocketException) {
                    translatedException = new StorageException(StorageErrorCode.SERVICE_INTERNAL_ERROR.toString(),
                            "An unknown failure occurred : ".concat(e.getCause().getMessage()),
                            HttpURLConnection.HTTP_INTERNAL_ERROR, null, e);
                }
                else {
                    translatedException = StorageException.translateException(getLastRequestObject(opContext), e,
                            opContext);
                }

                task.getResult().setException(translatedException);
                if (!(e.getCause() instanceof IOException) && !(e.getCause() instanceof SocketException)) {
                    throw translatedException;
                }
            }
            catch (final InvalidKeyException e) {
                // Non Retryable, just throw
                translatedException = StorageException
                        .translateException(getLastRequestObject(opContext), e, opContext);
                task.getResult().setException(translatedException);
                throw translatedException;
            }
            catch (final URISyntaxException e) {
                // Non Retryable, just throw
                translatedException = StorageException
                        .translateException(getLastRequestObject(opContext), e, opContext);
                task.getResult().setException(translatedException);
                throw translatedException;
            }
            catch (final TableServiceException e) {
                task.getResult().setStatusCode(e.getHttpStatusCode());
                task.getResult().setStatusMessage(e.getMessage());
                task.getResult().setException(e);
                if (!e.isRetryable()) {
                    throw e;
                }
                else {
                    translatedException = e;
                }
            }
            catch (final StorageException e) {
                // Non Retryable, just throw
                // do not translate StorageException
                task.getResult().setException(e);
                throw e;
            }
            catch (final Exception e) {
                // Non Retryable, just throw
                translatedException = StorageException
                        .translateException(getLastRequestObject(opContext), e, opContext);
                task.getResult().setException(translatedException);
                throw translatedException;
            }

            // Evaluate Retry Policy
            retryRes = policy.shouldRetry(currentRetryCount, task.getResult().getStatusCode(), task.getResult()
                    .getException(), opContext);
            if (!retryRes.isShouldRetry()) {
                throw translatedException;
            }
            else {
                retryRes.doSleep();
                currentRetryCount++;
            }
        }
    }

    /**
     * Gets the input stream from the request
     * 
     * @param request
     *            the request to process
     * @param opContext
     *            an object used to track the execution of the operation
     * @param currResult
     *            A {@link RequestResult} object that represents the current request result.
     * @return the input stream from the request
     * @throws IOException
     *             if there is an error making the connection
     */
    public static InputStream getInputStream(final HttpURLConnection request, final OperationContext opContext,
            final RequestResult currResult) throws IOException {

        opContext.setCurrentRequestObject(request);
        currResult.setStartDate(new Date());

        if (opContext.getSendingRequestEventHandler().hasListeners()) {
            opContext.getSendingRequestEventHandler()
                    .fireEvent(new SendingRequestEvent(opContext, request, currResult));
        }

        try {
            return request.getInputStream();
        }
        catch (final IOException ex) {
            getResponseCode(currResult, request, opContext);
            throw ex;
        }
    }

    /**
     * Gets the last request object in a safe way, returns null if there was not last request result.
     * 
     * @param opContext
     *            an object used to track the execution of the operation
     * @return the last request object in a safe way, returns null if there was not last request result.
     */
    private static HttpURLConnection getLastRequestObject(final OperationContext opContext) {
        if (opContext == null || opContext.getCurrentRequestObject() == null) {
            return null;
        }

        return opContext.getCurrentRequestObject();
    }

    /**
     * Gets the response code form the request
     * 
     * @param currResult
     *            the current RequestResult object
     * @param request
     *            the request to process
     * @param opContext
     *            an object used to track the execution of the operation
     * @throws IOException
     *             if there is an error making the connection
     */
    public static void getResponseCode(final RequestResult currResult, final HttpURLConnection request,
            final OperationContext opContext) throws IOException {
        // Send the request
        currResult.setStatusCode(request.getResponseCode());
        currResult.setStatusMessage(request.getResponseMessage());

        currResult.setStopDate(new Date());
        currResult.setServiceRequestID(BaseResponse.getRequestId(request));
        currResult.setEtag(BaseResponse.getEtag(request));
        currResult.setRequestDate(BaseResponse.getDate(request));
        currResult.setContentMD5(BaseResponse.getContentMD5(request));

        if (opContext.getResponseReceivedEventHandler().hasListeners()) {
            opContext.getResponseReceivedEventHandler().fireEvent(
                    new ResponseReceivedEvent(opContext, request, currResult));
        }
    }

    /**
     * Gets the response for a given HttpURLConnection, populates the opContext with the result, and fires an event if
     * it has listeners.
     * 
     * @param request
     *            the request to process
     * @param opContext
     *            an object used to track the execution of the operation
     * @param currResult
     *            A {@link RequestResult} object that represents the current request result.
     * @throws IOException
     *             if there is an error making the connection
     */
    public static void processRequest(final HttpURLConnection request, final OperationContext opContext,
            final RequestResult currResult) throws IOException {

        opContext.setCurrentRequestObject(request);
        currResult.setStartDate(new Date());

        if (opContext.getSendingRequestEventHandler().hasListeners()) {
            opContext.getSendingRequestEventHandler()
                    .fireEvent(new SendingRequestEvent(opContext, request, currResult));
        }

        // Send the request
        currResult.setStatusCode(request.getResponseCode());
        currResult.setStatusMessage(request.getResponseMessage());

        currResult.setStopDate(new Date());
        currResult.setServiceRequestID(BaseResponse.getRequestId(request));
        currResult.setEtag(BaseResponse.getEtag(request));
        currResult.setRequestDate(BaseResponse.getDate(request));
        currResult.setContentMD5(BaseResponse.getContentMD5(request));

        if (opContext.getResponseReceivedEventHandler().hasListeners()) {
            opContext.getResponseReceivedEventHandler().fireEvent(
                    new ResponseReceivedEvent(opContext, request, currResult));
        }
    }

    /**
     * Private default Ctor for Utility Class
     */
    private ExecutionEngine() {
        // private ctor
    }
}
