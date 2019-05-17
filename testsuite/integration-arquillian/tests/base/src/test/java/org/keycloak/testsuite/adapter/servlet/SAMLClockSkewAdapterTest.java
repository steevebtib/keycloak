/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.adapter.servlet;

import java.util.List;
import javax.ws.rs.core.Response;
import org.apache.http.util.EntityUtils;
import org.hamcrest.Matcher;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.keycloak.adapters.rotation.PublicKeyLocator;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.adapter.AbstractServletsAdapterTest;
import org.keycloak.testsuite.adapter.filter.AdapterActionsFilter;
import org.keycloak.testsuite.arquillian.AppServerTestEnricher;
import org.keycloak.testsuite.arquillian.annotation.AppServerContainer;
import org.keycloak.testsuite.util.DroneUtils;
import org.keycloak.testsuite.util.SamlClientBuilder;
import org.keycloak.testsuite.utils.arquillian.ContainerConstants;
import org.keycloak.testsuite.utils.io.IOUtil;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.keycloak.testsuite.adapter.AbstractServletsAdapterTest.samlServletDeployment;
import static org.keycloak.testsuite.util.SamlClient.Binding.POST;


@AppServerContainer(ContainerConstants.APP_SERVER_UNDERTOW)
@AppServerContainer(ContainerConstants.APP_SERVER_WILDFLY)
@AppServerContainer(ContainerConstants.APP_SERVER_WILDFLY_DEPRECATED)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP6)
@AppServerContainer(ContainerConstants.APP_SERVER_EAP71)
@AppServerContainer(ContainerConstants.APP_SERVER_TOMCAT7)
@AppServerContainer(ContainerConstants.APP_SERVER_TOMCAT8)
@AppServerContainer(ContainerConstants.APP_SERVER_TOMCAT9)
@AppServerContainer(ContainerConstants.APP_SERVER_JETTY92)
@AppServerContainer(ContainerConstants.APP_SERVER_JETTY93)
@AppServerContainer(ContainerConstants.APP_SERVER_JETTY94)
public class SAMLClockSkewAdapterTest extends AbstractServletsAdapterTest {

    private static final String CONTEXT_ROOT = "sales-post-clock-skew";
    private static final String DEPLOYMENT_NAME_3_SEC = CONTEXT_ROOT + "_3Sec";
    private static final String DEPLOYMENT_NAME_30_SEC = CONTEXT_ROOT + "_30Sec";

    @ArquillianResource private Deployer deployer;

    @Deployment(name = DEPLOYMENT_NAME_3_SEC, managed = false)
    protected static WebArchive salesPostClockSkewServlet3Sec() {
        return samlServletDeployment(CONTEXT_ROOT, DEPLOYMENT_NAME_3_SEC, CONTEXT_ROOT + "/WEB-INF/web.xml", 3, AdapterActionsFilter.class, PublicKeyLocator.class, SendUsernameServlet.class);
    }
    @Deployment(name = DEPLOYMENT_NAME_30_SEC, managed = false)
    protected static WebArchive salesPostClockSkewServlet30Sec() {
        return samlServletDeployment(CONTEXT_ROOT, DEPLOYMENT_NAME_30_SEC, CONTEXT_ROOT + "/WEB-INF/web.xml", 30, AdapterActionsFilter.class, PublicKeyLocator.class, SendUsernameServlet.class);
    }

    @Override
    public void addAdapterTestRealms(List<RealmRepresentation> testRealms) {
        testRealms.add(IOUtil.loadRealm("/adapter-test/keycloak-saml/testsaml.json"));
    }

    private String getClockSkewSerlverUrl() throws Exception {
        return AppServerTestEnricher.getAppServerContextRoot() + "/" + CONTEXT_ROOT;
    }

    private void setAdapterAndServerTimeOffset(int timeOffset) throws Exception {
        super.setAdapterAndServerTimeOffset(timeOffset, getClockSkewSerlverUrl() + "/unsecured");
        DroneUtils.getCurrentDriver().navigate().to(getClockSkewSerlverUrl());
    }

    private void assertOutcome(int timeOffset, Matcher matcher) throws Exception {
        try {
            String resultPage = new SamlClientBuilder()
                    .navigateTo(getClockSkewSerlverUrl())
                    .processSamlResponse(POST).build()
                    .login().user(bburkeUser).build()
                    .processSamlResponse(POST)
                    .transformDocument(doc -> {
                        setAdapterAndServerTimeOffset(timeOffset);
                        return doc;
                    }).build().executeAndTransform(resp -> EntityUtils.toString(resp.getEntity()));

            Assert.assertThat(resultPage, matcher);
        } finally {
            setAdapterAndServerTimeOffset(0);
        }
    }

    private void assertTokenIsNotValid(int timeOffset) throws Exception {
        deployer.deploy(DEPLOYMENT_NAME_3_SEC);

        try {
            // undertow adapter redirects directly to error page defined in web.xml
            if (APP_SERVER_CONTAINER.contains("undertow")) {
                assertOutcome(timeOffset, allOf(
                    containsString("There was an error"),
                    containsString("HTTP status code: " + Response.Status.FORBIDDEN.getStatusCode())
                ));
            } else {
                assertOutcome(timeOffset, allOf(
                    not(containsString("request-path: principal=bburke")),
                    containsString("SAMLRequest"),
                    containsString("FORM METHOD=\"POST\"")
                ));
            }

        } finally {
            deployer.undeploy(DEPLOYMENT_NAME_3_SEC);
        }
    }

    @Test
    public void testTokenHasExpired() throws Exception {
        assertTokenIsNotValid(65);
    }

    @Test
    public void testTokenIsNotYetValid() throws Exception {
        assertTokenIsNotValid(-65);
    }


    @Test
    public void testTokenTimeIsValid() throws Exception {
        deployer.deploy(DEPLOYMENT_NAME_30_SEC);

        try {
            assertOutcome(-10, allOf(containsString("request-path:"), containsString("principal=bburke")));
        } finally {
            deployer.undeploy(DEPLOYMENT_NAME_30_SEC);
        }
    }

}
