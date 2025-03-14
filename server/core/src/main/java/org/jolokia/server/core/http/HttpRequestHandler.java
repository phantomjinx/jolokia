package org.jolokia.server.core.http;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.*;

import org.jolokia.server.core.backend.BackendManager;
import org.jolokia.server.core.config.ConfigKey;
import org.jolokia.server.core.service.api.JolokiaContext;
import org.jolokia.server.core.request.*;
import org.jolokia.json.*;
import org.jolokia.json.parser.JSONParser;
import org.jolokia.json.parser.ParseException;

/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * Request handler with no dependency on the servlet API so that it can be used in
 * several different environments (like for the Sun JDK 11+ {@link com.sun.net.httpserver.HttpServer}).
 *
 * @author roland
 * @since Mar 3, 2010
 */
public class HttpRequestHandler {

    // handler for contacting the MBean server(s)
    private final BackendManager backendManager;

    // Overall context
    private final JolokiaContext jolokiaCtx;

    private final boolean includeRequestGlobal;

    /**
     * Request handler for parsing HTTP request and dispatching to the appropriate
     * request handler (with help of the backend manager)
     *
     * @param pJolokiaCtx jolokia context
     */
    public HttpRequestHandler(JolokiaContext pJolokiaCtx) {
        backendManager = new BackendManager(pJolokiaCtx);
        jolokiaCtx = pJolokiaCtx;
        includeRequestGlobal = pJolokiaCtx.getConfig(ConfigKey.INCLUDE_REQUEST) == null
            || Boolean.parseBoolean(pJolokiaCtx.getConfig(ConfigKey.INCLUDE_REQUEST));
    }

    /**
     * Handle a GET request
     *
     * @param pUri URI leading to this request
     * @param pPathInfo path of the request
     * @param pParameterMap parameters of the GET request  @return the response
     */
    public JSONStructure handleGetRequest(String pUri, String pPathInfo, Map<String, String[]> pParameterMap)
        throws EmptyResponseException {
        String pathInfo = extractPathInfo(pUri, pPathInfo);

        JolokiaRequest jmxReq =
                JolokiaRequestFactory.createGetRequest(pathInfo, getProcessingParameter(pParameterMap));

        if (jolokiaCtx.isDebug()) {
            jolokiaCtx.debug("URI: " + pUri);
            jolokiaCtx.debug("Path-Info: " + pathInfo);
            jolokiaCtx.debug("Request: " + jmxReq.toString());
        }
        return executeRequest(jmxReq);
    }

    /**
     * Get processing parameters from a string-string map
     *
     * @param pParameterMap params to extra. A parameter {@link ConfigKey#PATH_QUERY_PARAM} is used as extra path info
     * @return the processing parameters
     */
    ProcessingParameters getProcessingParameter(Map<String, String[]> pParameterMap) throws BadRequestException {
        Map<ConfigKey,String> config = new HashMap<>();
        if (pParameterMap != null) {
            extractRequestParameters(config, pParameterMap);
            validateRequestParameters(config);
            extractDefaultRequestParameters(config);
        }
        return new ProcessingParameters(config);
    }

    /**
     * Handle the input stream as given by a POST request
     *
     *
     * @param pUri URI leading to this request
     * @param pInputStream input stream of the post request
     * @param pEncoding optional encoding for the stream. If null, the default encoding is used
     * @param pParameterMap additional processing parameters
     * @return the JSON object containing the json results for one or more {@link JolokiaRequest} contained
     *         within the answer.
     *
     * @throws IOException if reading from the input stream fails
     */
    @SuppressWarnings("unchecked")
    public JSONStructure handlePostRequest(String pUri, InputStream pInputStream, String pEncoding, Map<String, String[]> pParameterMap)
            throws IOException, EmptyResponseException {
        if (jolokiaCtx.isDebug()) {
            jolokiaCtx.debug("URI: " + pUri);
        }

        ProcessingParameters parameters = getProcessingParameter(pParameterMap);
        Object jsonRequest = extractJsonRequest(pInputStream,pEncoding);
        if (jsonRequest instanceof JSONArray) {
            List<JolokiaRequest> jolokiaRequests = JolokiaRequestFactory.createPostRequests((List<?>) jsonRequest, parameters);

            JSONArray responseList = new JSONArray(jolokiaRequests.size());
            for (JolokiaRequest jmxReq : jolokiaRequests) {
                if (jolokiaCtx.isDebug()) {
                    jolokiaCtx.debug("Request: " + jmxReq.toString());
                }
                // Call handler and retrieve return value
                JSONObject resp = executeRequest(jmxReq);
                responseList.add(resp);
            }
            return responseList;
        } else if (jsonRequest instanceof JSONObject) {
            JolokiaRequest jmxReq = JolokiaRequestFactory.createPostRequest((Map<String, ?>) jsonRequest, parameters);
            return executeRequest(jmxReq);
        } else {
            throw new BadRequestException("Invalid JSON Request. Expected Object or Array");
        }
    }

    /**
     * Handling an option request which is used for preflight checks before a CORS based browser request is
     * sent (for certain circumstances).
     * <p>
     * See the <a href="http://www.w3.org/TR/cors/">CORS specification</a>
     * (section 'preflight checks') for more details.
     *
     * @param pOrigin the origin to check. If <code>null</code>, no headers are returned
     * @param pRequestHeaders extra headers to check against
     * @return headers to set
     */
    public Map<String, String> handleCorsPreflightRequest(String pOrigin, String pRequestHeaders) {
        Map<String,String> ret = new HashMap<>();
        if (jolokiaCtx.isOriginAllowed(pOrigin,false)) {
            // CORS is allowed, we set exactly the origin in the header, so there are no problems with authentication
            ret.put("Access-Control-Allow-Origin",pOrigin == null || "null".equals(pOrigin) ? "*" : pOrigin);
            if (pRequestHeaders != null) {
                ret.put("Access-Control-Allow-Headers",pRequestHeaders);
            }
            // Fix for CORS with authentication (#104)
            ret.put("Access-Control-Allow-Credentials","true");
            // Allow for one year. Changes in access.xml are reflected directly in the CORS request itself
            ret.put("Access-Control-Max-Age","" + 3600 * 24 * 365);
        }
        return ret;
    }

    private Object extractJsonRequest(InputStream pInputStream, String pEncoding) throws IOException {
        InputStreamReader reader;
        try {
            reader =
                    pEncoding != null ?
                            new InputStreamReader(pInputStream, pEncoding) :
                            new InputStreamReader(pInputStream);
            JSONParser parser = new JSONParser();
            return parser.parse(reader);
        } catch (ParseException exp) {
            // JSON parsing error means we can't even know if it's bulk request or not, so HTTP 400
            throw new BadRequestException("Invalid JSON request", exp);
        }
    }

    /**
     * Execute a single {@link JolokiaRequest}. If a checked  exception occurs,
     * this gets translated into the appropriate JSON object which will get returned.
     * Note, that these exceptions gets *not* translated into an HTTP error, since they are
     * supposed <em>Jolokia</em> specific errors above the transport layer.
     *
     * @param pJmxReq the request to execute
     * @return the JSON representation of the answer.
     */
    private JSONObject executeRequest(JolokiaRequest pJmxReq) throws EmptyResponseException {
        // Call handler and retrieve return value
        try {
            return backendManager.handleRequest(pJmxReq);
        } catch (ReflectionException | InstanceNotFoundException | AttributeNotFoundException e) {
            return getErrorJSON(404,e, pJmxReq);
        } catch (MBeanException e) {
            return getErrorJSON(500,e.getTargetException(), pJmxReq);
        } catch (UnsupportedOperationException | JMException | IOException e) {
            return getErrorJSON(500,e, pJmxReq);
        } catch (IllegalArgumentException e) {
            return getErrorJSON(400,e, pJmxReq);
        } catch (SecurityException e) {
            // Wipe out stacktrace
            return getErrorJSON(403,new Exception(e.getMessage()), pJmxReq);
        } catch (RuntimeMBeanException e) {
            // Use wrapped exception
            return errorForUnwrappedException(e,pJmxReq);
        }
    }

    /**
     * Utility method for handling single runtime exceptions and errors. This method is called
     * in addition to and after {@link #executeRequest(JolokiaRequest)} to catch additional errors.
     * They are two different methods because of bulk requests, where each individual request can
     * lead to an error. So, each individual request is wrapped with the error handling of
     * {@link #executeRequest(JolokiaRequest)}
     * whereas the overall handling is wrapped with this method. It is hence more coarse grained,
     * leading typically to a status code of 500.
     * <p>
     * Summary: This method should be used as last security belt is some exception should escape
     * from a single request processing in {@link #executeRequest(JolokiaRequest)}.
     *
     * @param pThrowable exception to handle
     * @return its JSON representation
     */
    public JSONObject handleThrowable(Throwable pThrowable) {
        if (pThrowable instanceof IllegalArgumentException) {
            return getErrorJSON(400, pThrowable, null);
        } else if (pThrowable instanceof SecurityException) {
            // Wipe out stacktrace
            return getErrorJSON(403, new Exception(pThrowable.getMessage()), null);
        } else {
            return getErrorJSON(500, pThrowable, null);
        }
    }

    /**
     * Get the JSON representation for an exception.
     *
     *
     * @param pErrorCode the HTTP error code to return
     * @param pExp the exception or error occured
     * @param pJmxReq request from where to get processing options
     * @return the json representation
     */
    public JSONObject getErrorJSON(int pErrorCode, Throwable pExp, JolokiaRequest pJmxReq) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("status",pErrorCode);
        jsonObject.put("error",getExceptionMessage(pExp));
        jsonObject.put("error_type", pExp.getClass().getName());
        addErrorInfo(jsonObject, pExp, pJmxReq);
        if (jolokiaCtx.isDebug()) {
           jolokiaCtx.error("Error " + pErrorCode,pExp);
        }
        if (pJmxReq != null) {
            String includeRequestLocal = pJmxReq.getParameter(ConfigKey.INCLUDE_REQUEST);
            if ((includeRequestGlobal && !"false".equals(includeRequestLocal))
                || (!includeRequestGlobal && "true".equals(includeRequestLocal))) {
                jsonObject.put("request",pJmxReq.toJSON());
            }
        }
        return jsonObject;
    }

    /**
     * Check whether the given host and/or address is allowed to access this agent.
     *
     * @param pRequestScheme scheme used to make the request ('http' or 'https')
     * @param pHost host to check
     * @param pAddress address to check
     * @param pOrigin (optional) origin header to check also.
     */
    public void checkAccess(String pRequestScheme, String pHost, String pAddress, String pOrigin) {
        if (!jolokiaCtx.isRemoteAccessAllowed(pHost != null ? new String[] { pHost, pAddress } : new String[] { pAddress })) {
            throw new SecurityException("No access from client " + pAddress + " allowed");
        }
        if (!jolokiaCtx.isOriginAllowed(pOrigin, true)) {
            throw new SecurityException("Origin " + pOrigin + " is not allowed to call this agent");
        }

        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Origin
        if (!jolokiaCtx.ignoreScheme() && "http".equals(pRequestScheme) && pOrigin != null && !"null".equals(pOrigin)) {
            try {
                String originScheme = new URL(pOrigin).getProtocol();
                // Requests with HTTPS origin should not be responded over HTTP,
                // as it compromises data confidentiality and integrity.
                if ("https".equals(originScheme)) {
                    throw new SecurityException("Secure origin " + pOrigin + " should not be processed over HTTP");
                }
            } catch (MalformedURLException e) {
                // Ignore it, should be safe as origin is not https anyway
            }
        }
    }

    /**
     * Check whether for the given host is a cross-browser request allowed. This check is delegated to the
     * backendmanager which is responsible for the security configuration.
     * Also, some sanity checks are applied.
     *
     * @param pOrigin the origin URL to check against
     * @return the origin to put in the response header or null if none is to be set
     */
    public String extractCorsOrigin(String pOrigin) {
        if (pOrigin != null) {
            // Prevent HTTP response splitting attacks
            String origin  = pOrigin.replaceAll("[\\n\\r]*","");
            if (jolokiaCtx.isOriginAllowed(origin,false)) {
                return "null".equals(origin) ? "*" : origin;
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract configuration parameters from the given HTTP request parameters
     * @param pConfig
     * @param pParameterMap
     */
    private void extractRequestParameters(Map<ConfigKey, String> pConfig, Map<String, String[]> pParameterMap) {
        for (Map.Entry<String,String[]> entry : pParameterMap.entrySet()) {
            String[] values = entry.getValue();
            if (values != null && values.length > 0) {
                ConfigKey cKey = ConfigKey.getRequestConfigKey(entry.getKey());
                if (cKey != null) {
                    Object value = values[0];
                    pConfig.put(cKey, value != null ? value.toString() : null);
                }
            }
        }
    }

    /**
     * Validation of parameters. Should be called for provided parameter values. Not necessary for built-in/default
     * values.
     * @param config
     */
    private void validateRequestParameters(Map<ConfigKey, String> config) throws BadRequestException {
        // parameters that may be passed with HTTP request:
        //  + callback
        //  + canonicalNaming
        //  + ifModifiedSince
        //  + ignoreErrors (validated in org.jolokia.server.core.request.JolokiaRequest.initParameters())
        //  + includeRequest
        //  + includeStackTrace (to be validated in org.jolokia.server.core.http.HttpRequestHandler.addErrorInfo())
        //  + listCache
        //  + listKeys
        //  + maxCollectionSize
        //  + maxDepth
        //  + maxObjects
        //  + mimeType
        //  + p
        //  + serializeException
        //  + serializeLong
        for (Map.Entry<ConfigKey, String> e : config.entrySet()) {
            ConfigKey key = e.getKey();
            String value = e.getValue();
            Class<?> type = key.getType();

            if (type == null) {
                continue;
            }

            if (type == Boolean.class) {
                String v = value.trim().toLowerCase();
                if (!(ConfigKey.enabledValues.contains(v) || ConfigKey.disabledValues.contains(v))) {
                    throw new BadRequestException("Invalid value of " + key.getKeyValue() + " parameter");
                }
            } else if (type == Integer.class) {
                String v = value.trim();
                try {
                    Integer.parseInt(v);
                } catch (NumberFormatException ex) {
                    throw new BadRequestException("Invalid value of " + key.getKeyValue() + " parameter");
                }
            } else if (type == String.class) {
                // validate selected keys
                if (key == ConfigKey.INCLUDE_STACKTRACE) {
                    String v = value.trim().toLowerCase();
                    if (!(ConfigKey.enabledValues.contains(v) || ConfigKey.disabledValues.contains(v)
                            || v.equals("runtime"))) {
                        throw new BadRequestException("Invalid value of " + ConfigKey.INCLUDE_STACKTRACE.getKeyValue() + " parameter");
                    }
                }
            }
        }
    }

    // Add from the global configuration all request relevant parameters which have not
    // already been set in the given map
    private void extractDefaultRequestParameters(Map<ConfigKey, String> pConfig) {
        Set<ConfigKey> globalRequestConfigKeys = jolokiaCtx.getConfigKeys();
        for (ConfigKey key : globalRequestConfigKeys) {
            if (key.isRequestConfig() && !pConfig.containsKey(key)) {
                pConfig.put(key,jolokiaCtx.getConfig(key));
            }
        }
    }

    private void addErrorInfo(JSONObject pErrorResp, Throwable pExp, JolokiaRequest pJmxReq) {
        if (Boolean.parseBoolean(jolokiaCtx.getConfig(ConfigKey.ALLOW_ERROR_DETAILS))) {
            String includeStackTrace = pJmxReq != null ?
                    pJmxReq.getParameter(ConfigKey.INCLUDE_STACKTRACE) : "false";
            if (includeStackTrace.equalsIgnoreCase("true") ||
                (includeStackTrace.equalsIgnoreCase("runtime") && pExp instanceof RuntimeException)) {
                StringWriter writer = new StringWriter();
                pExp.printStackTrace(new PrintWriter(writer));
                pErrorResp.put("stacktrace", writer.toString());
            }
            if (pJmxReq != null && pJmxReq.getParameterAsBool(ConfigKey.SERIALIZE_EXCEPTION)) {
                pErrorResp.put("error_value", backendManager.convertExceptionToJson(pExp, pJmxReq));
            }
        }
    }

    // Extract class and exception message for an error message
    private String getExceptionMessage(Throwable pException) {
        String message = pException.getLocalizedMessage();
        return pException.getClass().getName() + (message != null ? " : " + message : "");
    }

    // Unwrap an exception to get to the 'real' exception
    // and extract the error code accordingly
    private JSONObject errorForUnwrappedException(Exception e, JolokiaRequest pJmxReq) {
        Throwable cause = e.getCause();
        int code = cause instanceof IllegalArgumentException ? 400 : cause instanceof SecurityException ? 403 : 500;
        return getErrorJSON(code,cause, pJmxReq);
    }

    // Path info might need some special handling in case when the URL
    // contains two following slashes. These slashes get collapsed
    // when calling getPathInfo() but are still present in the URI.
    // This situation can happen, when slashes are escaped and the last char
    // of a path part is such an escaped slash
    // (e.g. "read/domain:type=name!//attribute")
    // In this case, we extract the path info on our own

    private static final Pattern PATH_PREFIX_PATTERN = Pattern.compile("^/?[^/]+/");

    private String extractPathInfo(String pUri, String pPathInfo) {
        if (pUri.contains("!//")) {
            // Special treatment for trailing slashes in paths
            Matcher matcher = PATH_PREFIX_PATTERN.matcher(pPathInfo);
            if (matcher.find()) {
                String prefix = matcher.group();
                String pathInfoEncoded = pUri.replaceFirst("^.*?" + prefix, prefix);
                return URLDecoder.decode(pathInfoEncoded, StandardCharsets.UTF_8);
            }
        }
        return pPathInfo;
    }
}
