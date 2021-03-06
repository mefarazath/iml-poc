/*
*  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.gateway.mediators.oidc.response.processor;

import com.nimbusds.jwt.ReadOnlyJWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.gateway.core.config.ParameterHolder;
import org.wso2.carbon.gateway.core.flow.AbstractMediator;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.Constants;
import org.wso2.carbon.messaging.DefaultCarbonMessage;
import org.wso2.identity.bus.framework.AuthenticationContext;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Mediator Implementation
 */
public class OIDCResponseProcessor extends AbstractMediator {

    private static final Logger log = LoggerFactory.getLogger(OIDCResponseProcessor.class);
    private static final String PROPERTY_IS_SUBJECT = "isSubject";
    private static final String PROPERTY_SUBJECT_CLAIM = "subjectClaim";
    private static final String PROPERTY_IS_ATTRIBUTE = "isAttribute";
    private String logMessage = "Message received at Sample Mediator";   // Sample Mediator specific variable
    private Map<String, String> parameters = new HashMap<>();


    @Override
    public String getName() {
        return "OIDCResponseProcessor";
    }

    /**
     * Mediate the message.
     * <p/>
     * This is the execution point of the mediator.
     *
     * @param carbonMessage  MessageContext to be mediated
     * @param carbonCallback Callback which can be use to call the previous step
     * @return whether mediation is success or not
     */
    @Override
    public boolean receive(CarbonMessage carbonMessage, CarbonCallback carbonCallback) throws Exception {

        if (log.isDebugEnabled()) {
            log.debug("Message received at " + getName());
        }

        AuthenticationSuccessResponse successResponse;
        DefaultCarbonMessage message = new DefaultCarbonMessage();
        String state;

        if (carbonMessage.getProperty(org.wso2.carbon.gateway.core.Constants.SERVICE_METHOD).equals("GET")) {
            try {
                // NOTE: This approach is used to overcome the issue of, IS 5.1.0 sending the id_token as a query
                // param instead of a URL fragment. Ideally we will not be needing the logic inside the 'try' block.
                successResponse = AuthenticationSuccessResponse.parse(new URI((String) carbonMessage.getProperty
                        (Constants.TO)));
                Map<String, String> query_pairs = new HashMap<>();
                URI uri = new URI((String) carbonMessage.getProperty(Constants.TO));
                String query = uri.getQuery();
                String[] pairs = query.split("&");

                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    query_pairs.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()),
                                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()));
                }

                state = query_pairs.get("state");

            } catch (ParseException e) {

                URI uri = new URI(carbonMessage.getProperty(Constants.PROTOCOL).toString().toLowerCase(),
                                  null,
                                  carbonMessage.getProperty(Constants.HOST).toString(),
                                  Integer.parseInt(carbonMessage.getProperty(Constants.LISTENER_PORT).toString()),
                                  carbonMessage.getProperty(Constants.TO).toString(),
                                  null,
                                  null);

                String response = getJavascript(uri.toASCIIString());
                message.setStringMessageBody(response);

                int contentLength = response.getBytes().length;

                Map<String, String> transportHeaders = new HashMap<>();
                transportHeaders.put(Constants.HTTP_CONNECTION, Constants.KEEP_ALIVE);
                transportHeaders.put(Constants.HTTP_CONTENT_ENCODING, Constants.GZIP);
                transportHeaders.put(Constants.HTTP_CONTENT_TYPE, "text/html");
                transportHeaders.put(Constants.HTTP_CONTENT_LENGTH, (String.valueOf(contentLength)));

                message.setHeaders(transportHeaders);

                message.setProperty(Constants.HTTP_STATUS_CODE, 200);
                message.setProperty(Constants.DIRECTION, Constants.DIRECTION_RESPONSE);
                message.setProperty(Constants.CALL_BACK, carbonCallback);

                carbonCallback.done(message);
                return true;
            }
        } else if (carbonMessage.getProperty(org.wso2.carbon.gateway.core.Constants.SERVICE_METHOD).equals("POST")) {

            String contentLength = carbonMessage.getHeader(Constants.HTTP_CONTENT_LENGTH);
            byte[] bytes = new byte[Integer.parseInt(contentLength)];

            List<ByteBuffer> fullMessageBody = carbonMessage.getFullMessageBody();

            int offset = 0;

            for (ByteBuffer byteBuffer : fullMessageBody) {
                ByteBuffer duplicate = byteBuffer.duplicate();
                duplicate.get(bytes, offset, byteBuffer.capacity());
                offset = offset + duplicate.capacity();
            }

            message.setStringMessageBody("");

            String encodedParams = new String(bytes);
            String fragment = URLDecoder.decode(encodedParams, StandardCharsets.UTF_8.name()).split("=", 2)[1];
            successResponse = AuthenticationSuccessResponse.parse(new URI(carbonMessage.getProperty
                    (Constants.TO) + "#" + fragment));
            state = successResponse.getState().getValue();
        } else {

            return false;
        }

        SignedJWT signedJWT = (SignedJWT) successResponse.getIDToken();
        ReadOnlyJWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();

        //TODO JWT Sig validation

        AuthenticationContext authenticationContext = OIDCResponseProcessorDataHolder.getInstance()
                .getAuthenticationContext();
        if (Boolean.parseBoolean(parameters.get(PROPERTY_IS_SUBJECT))) {

            String subjectClaim = parameters.get(PROPERTY_SUBJECT_CLAIM);
            String subjectClaimValue;

            if (subjectClaim == null || subjectClaim.isEmpty()) {
                subjectClaimValue = jwtClaimsSet.getSubject();
            } else {
                subjectClaimValue = jwtClaimsSet.getStringClaim(subjectClaim);

                if (subjectClaimValue == null || subjectClaimValue.isEmpty()) {
                    subjectClaim = "sub";
                    subjectClaimValue = jwtClaimsSet.getSubject();
                }
            }

            Map<String, Object> responseContext = (Map<String, Object>) authenticationContext.getFromContext(state);

            final String finalSubjectClaim = subjectClaim;
            final String finalSubjectClaimValue = subjectClaimValue;

            responseContext.put("subject", new HashMap<String, String>() {
                {
                    put(finalSubjectClaim, finalSubjectClaimValue);
                }
            });

            authenticationContext.addToContext(state, responseContext);
        }
        if (Boolean.parseBoolean(parameters.get(PROPERTY_IS_ATTRIBUTE))) {
            Map<String, Object> responseContext = (Map<String, Object>) authenticationContext.getFromContext(state);
            responseContext.put("attributes", new HashMap<String, String>() {
                {
                    for (Map.Entry<String, Object> entry : jwtClaimsSet.getCustomClaims().entrySet()) {
                        put(entry.getKey(), entry.getValue().toString());
                    }
                }
            });

            authenticationContext.addToContext(state, responseContext);
        }

        message.setProperty("signedJWT", signedJWT);
        message.setProperty("sessionID", state);

        return next(message, carbonCallback);
    }

    /**
     * Set Parameters
     *
     * @param parameterHolder holder which contains key-value pairs of parameters
     */
    @Override
    public void setParameters(ParameterHolder parameterHolder) {
        String paramString = parameterHolder.getParameter("parameters").getValue();
        String[] paramArray = paramString.split(",");

        for (String param : paramArray) {
            String[] params = param.split("=", 2);
            if (params.length == 2) {
                parameters.put(params[0].trim(), params[1].trim());
            }
        }
    }


    /**
     * This is a sample mediator specific method
     */
    public void setLogMessage(String logMessage) {
        this.logMessage = logMessage;
    }


    private static String getJavascript(String callbackURL) {
        String responseBody = "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3" +
                              ".org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n" +
                              "\n" +
                              "<html>\n" +
                              "    <head>\n" +
                              "        <title></title>\n" +
                              "    </head>\n" +
                              "    <body>\n" +
                              "        <p>You are now redirected to $url If the redirection fails, please click the " +
                              "post button.</p>\n" +
                              "        <form action='" + callbackURL + "' method='post'>\n" +
                              "            <p><input name='fragment' id='id_token' type='hidden' value=''> <button " +
                              "type='submit'>POST</button></p>\n" +
                              "        </form>\n" +
                              "        <script type='text/javascript'>\n" +
                              "            document.getElementById('id_token').value = window.location.hash.substring" +
                              "(1);\n" +
                              "            document.forms[0].submit();\n" +
                              "        </script>\n" +
                              "    </body>\n" +
                              "</html>";

        return responseBody;
    }

}
