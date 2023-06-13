/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker.rest.api.v3;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.pulsar.broker.authentication.AuthenticationParameters;
import org.apache.pulsar.client.admin.Functions;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.Packages;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Tenants;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.functions.UpdateOptionsImpl;
import org.apache.pulsar.common.nar.NarClassLoader;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.RestException;
import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.instance.InstanceUtils;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.proto.Function.FunctionMetaData;
import org.apache.pulsar.functions.proto.Function.PackageLocationMetaData;
import org.apache.pulsar.functions.proto.Function.ProcessingGuarantees;
import org.apache.pulsar.functions.proto.Function.SinkSpec;
import org.apache.pulsar.functions.proto.Function.SourceSpec;
import org.apache.pulsar.functions.proto.Function.SubscriptionType;
import org.apache.pulsar.functions.runtime.RuntimeFactory;
import org.apache.pulsar.functions.source.TopicSchema;
import org.apache.pulsar.functions.utils.FunctionCommon;
import org.apache.pulsar.functions.utils.FunctionConfigUtils;
import org.apache.pulsar.functions.utils.functions.FunctionArchive;
import org.apache.pulsar.functions.utils.io.Connector;
import org.apache.pulsar.functions.worker.ConnectorsManager;
import org.apache.pulsar.functions.worker.FunctionMetaDataManager;
import org.apache.pulsar.functions.worker.FunctionRuntimeManager;
import org.apache.pulsar.functions.worker.FunctionsManager;
import org.apache.pulsar.functions.worker.LeaderService;
import org.apache.pulsar.functions.worker.PulsarWorkerService;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerUtils;
import org.apache.pulsar.functions.worker.rest.api.FunctionsImpl;
import org.apache.pulsar.functions.worker.rest.api.PulsarFunctionTestTemporaryDirectory;
import org.apache.pulsar.functions.worker.rest.api.v2.FunctionsApiV2Resource;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Unit test of {@link FunctionsApiV2Resource}.
 */
public class FunctionApiV3ResourceTest {

    private static final class TestFunction implements Function<String, String> {

        @Override
        public String process(String input, Context context) {
            return input;
        }
    }

    private static final class WrongFunction implements Consumer<String> {
        @Override
        public void accept(String s) {

        }
    }

    private static final String tenant = "test-tenant";
    private static final String namespace = "test-namespace";
    private static final String function = "test-function";
    private static final String outputTopic = "test-output-topic";
    private static final String outputSerdeClassName = TopicSchema.DEFAULT_SERDE;
    private static final String className = TestFunction.class.getName();
    private SubscriptionType subscriptionType = SubscriptionType.FAILOVER;
    private static final Map<String, String> topicsToSerDeClassName = new HashMap<>();
    static {
        topicsToSerDeClassName.put("persistent://public/default/test_src", TopicSchema.DEFAULT_SERDE);
    }
    private static final int parallelism = 1;

    private PulsarWorkerService mockedWorkerService;
    private PulsarAdmin mockedPulsarAdmin;
    private Tenants mockedTenants;
    private Namespaces mockedNamespaces;
    private Functions mockedFunctions;
    private TenantInfoImpl mockedTenantInfo;
    private List<String> namespaceList = new LinkedList<>();
    private FunctionMetaDataManager mockedManager;
    private FunctionRuntimeManager mockedFunctionRunTimeManager;
    private RuntimeFactory mockedRuntimeFactory;
    private Namespace mockedNamespace;
    private FunctionsImpl resource;
    private InputStream mockedInputStream;
    private FormDataContentDisposition mockedFormData;
    private FunctionMetaData mockedFunctionMetadata;
    private LeaderService mockedLeaderService;
    private Packages mockedPackages;
    private PulsarFunctionTestTemporaryDirectory tempDirectory;
    private static Map<String, MockedStatic> mockStaticContexts = new HashMap<>();

    private static final String SYSTEM_PROPERTY_NAME_FUNCTIONS_API_EXAMPLES_NAR_FILE_PATH =
            "pulsar-functions-api-examples.nar.path";

    public static File getPulsarApiExamplesNar() {
        return new File(Objects.requireNonNull(
                System.getProperty(SYSTEM_PROPERTY_NAME_FUNCTIONS_API_EXAMPLES_NAR_FILE_PATH)
                , "pulsar-functions-api-examples.nar file location must be specified with "
                        + SYSTEM_PROPERTY_NAME_FUNCTIONS_API_EXAMPLES_NAR_FILE_PATH + " system property"));
    }

    @BeforeMethod
    public void setup() throws Exception {
        this.mockedManager = mock(FunctionMetaDataManager.class);
        this.mockedFunctionRunTimeManager = mock(FunctionRuntimeManager.class);
        this.mockedTenantInfo = mock(TenantInfoImpl.class);
        this.mockedRuntimeFactory = mock(RuntimeFactory.class);
        this.mockedInputStream = mock(InputStream.class);
        this.mockedNamespace = mock(Namespace.class);
        this.mockedFormData = mock(FormDataContentDisposition.class);
        when(mockedFormData.getFileName()).thenReturn("test");
        this.mockedPulsarAdmin = mock(PulsarAdmin.class);
        this.mockedTenants = mock(Tenants.class);
        this.mockedNamespaces = mock(Namespaces.class);
        this.mockedFunctions = mock(Functions.class);
        this.mockedPackages = mock(Packages.class);
        this.mockedLeaderService = mock(LeaderService.class);
        this.mockedFunctionMetadata = FunctionMetaData.newBuilder().setFunctionDetails(createDefaultFunctionDetails()).build();
        namespaceList.add(tenant + "/" + namespace);

        this.mockedWorkerService = mock(PulsarWorkerService.class);
        when(mockedWorkerService.getFunctionMetaDataManager()).thenReturn(mockedManager);
        when(mockedWorkerService.getFunctionRuntimeManager()).thenReturn(mockedFunctionRunTimeManager);
        when(mockedWorkerService.getLeaderService()).thenReturn(mockedLeaderService);
        when(mockedFunctionRunTimeManager.getRuntimeFactory()).thenReturn(mockedRuntimeFactory);
        when(mockedWorkerService.getDlogNamespace()).thenReturn(mockedNamespace);
        when(mockedWorkerService.isInitialized()).thenReturn(true);
        when(mockedWorkerService.getBrokerAdmin()).thenReturn(mockedPulsarAdmin);
        when(mockedWorkerService.getFunctionAdmin()).thenReturn(mockedPulsarAdmin);
        when(mockedPulsarAdmin.tenants()).thenReturn(mockedTenants);
        when(mockedPulsarAdmin.namespaces()).thenReturn(mockedNamespaces);
        when(mockedPulsarAdmin.functions()).thenReturn(mockedFunctions);
        when(mockedPulsarAdmin.packages()).thenReturn(mockedPackages);
        when(mockedTenants.getTenantInfo(any())).thenReturn(mockedTenantInfo);
        when(mockedNamespaces.getNamespaces(any())).thenReturn(namespaceList);
        when(mockedLeaderService.isLeader()).thenReturn(true);
        when(mockedManager.getFunctionMetaData(any(), any(), any())).thenReturn(mockedFunctionMetadata);
        doNothing().when(mockedPackages).download(anyString(), anyString());

        // worker config
        WorkerConfig workerConfig = new WorkerConfig()
            .setWorkerId("test")
            .setWorkerPort(8080)
            .setFunctionMetadataTopicName("pulsar/functions")
            .setNumFunctionPackageReplicas(3)
            .setPulsarServiceUrl("pulsar://localhost:6650/");
        tempDirectory = PulsarFunctionTestTemporaryDirectory.create(getClass().getSimpleName());
        tempDirectory.useTemporaryDirectoriesForWorkerConfig(workerConfig);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(workerConfig);

        this.resource = spy(new FunctionsImpl(() -> mockedWorkerService));
    }

    @AfterMethod(alwaysRun = true)
    public void cleanup() {
        if (tempDirectory != null) {
            tempDirectory.delete();
        }
        mockStaticContexts.values().forEach(MockedStatic::close);
        mockStaticContexts.clear();
    }

    private <T> void mockStatic(Class<T> classStatic, Consumer<MockedStatic<T>> consumer) {
        final MockedStatic<T> mockedStatic = mockStaticContexts.computeIfAbsent(classStatic.getName(), name -> Mockito.mockStatic(classStatic));
        consumer.accept(mockedStatic);
    }

    private void mockWorkerUtils() {
        mockWorkerUtils(null);
    }

    private void mockWorkerUtils(Consumer<MockedStatic<WorkerUtils>> consumer) {
        mockStatic(WorkerUtils.class, ctx -> {
            ctx.when(() -> WorkerUtils.dumpToTmpFile(any())).thenCallRealMethod();
            if (consumer != null) {
                consumer.accept(ctx);
            }
        });
    }

    private void mockInstanceUtils() {
        mockStatic(InstanceUtils.class, ctx -> {
            ctx.when(() -> InstanceUtils.calculateSubjectType(any()))
                    .thenReturn(FunctionDetails.ComponentType.FUNCTION);
        });
    }

    //
    // Register Functions
    //

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant is not provided")
    public void testRegisterFunctionMissingTenant() {
        try {
            testRegisterFunctionMissingArguments(
                    null,
                    namespace,
                    function,
                    mockedInputStream,
                    topicsToSerDeClassName,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    className,
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace is not provided")
    public void testRegisterFunctionMissingNamespace() {
        try {
            testRegisterFunctionMissingArguments(
                tenant,
                null,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    null);
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function name is not provided")
    public void testRegisterFunctionMissingFunctionName() {
        try {
        testRegisterFunctionMissingArguments(
            tenant,
            namespace,
            null,
            mockedInputStream,
            topicsToSerDeClassName,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                null);
    } catch (RestException re){
        assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
        throw re;
    }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function package is not provided")
    public void testRegisterFunctionMissingPackage() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    null,
                    topicsToSerDeClassName,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    className,
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "No input topic\\(s\\) specified for the function")
    public void testRegisterFunctionMissingInputTopics() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    mockedInputStream,
                    null,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    className,
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function Package is not provided")
    public void testRegisterFunctionMissingPackageDetails() {
        try {
            testRegisterFunctionMissingArguments(
                tenant,
                namespace,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                null,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    null);
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function package does not have"
            + " the correct format. Pulsar cannot determine if the package is a NAR package or JAR package. Function "
            + "classname is not provided and attempts to load it as a NAR package produced the following error.*")
    public void testRegisterFunctionMissingClassName() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    mockedInputStream,
                    topicsToSerDeClassName,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    null,
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function class UnknownClass must be in class path")
    public void testRegisterFunctionWrongClassName() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    mockedInputStream,
                    topicsToSerDeClassName,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    "UnknownClass",
                    parallelism,
                    null);
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function parallelism must be a positive number")
    public void testRegisterFunctionWrongParallelism() {
        try {
            testRegisterFunctionMissingArguments(
                tenant,
                namespace,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                outputSerdeClassName,
                className,
                -2,
                null);
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class,
            expectedExceptionsMessageRegExp = "Output topic persistent://public/default/test_src is also being used as an input topic \\(topics must be one or the other\\)")
    public void testRegisterFunctionSameInputOutput() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    mockedInputStream,
                    topicsToSerDeClassName,
                    mockedFormData,
                    topicsToSerDeClassName.keySet().iterator().next(),
                    outputSerdeClassName,
                    className,
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Output topic " + function + "-output-topic/test:" + " is invalid")
    public void testRegisterFunctionWrongOutputTopic() {
        try {
            testRegisterFunctionMissingArguments(
                tenant,
                namespace,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                function + "-output-topic/test:",
                outputSerdeClassName,
                className,
                parallelism,
                null);
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Encountered error .*. when getting Function package from .*")
    public void testRegisterFunctionHttpUrl() {
        try {
            testRegisterFunctionMissingArguments(
                tenant,
                namespace,
                function,
                null,
                topicsToSerDeClassName,
                null,
                outputTopic,
                outputSerdeClassName,
                className,
                parallelism,
                "http://localhost:1234/test");
        } catch (RestException re){
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function class .*. does not implement the correct interface")
    public void testRegisterFunctionImplementWrongInterface() {
        try {
            testRegisterFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    mockedInputStream,
                    topicsToSerDeClassName,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    WrongFunction.class.getName(),
                    parallelism,
                    null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    private void testRegisterFunctionMissingArguments(
            String tenant,
            String namespace,
            String function,
            InputStream inputStream,
            Map<String, String> topicsToSerDeClassName,
            FormDataContentDisposition details,
            String outputTopic,
            String outputSerdeClassName,
            String className,
            Integer parallelism,
            String functionPkgUrl) {
        FunctionConfig functionConfig = new FunctionConfig();
        if (tenant != null) {
            functionConfig.setTenant(tenant);
        }
        if (namespace != null) {
            functionConfig.setNamespace(namespace);
        }
        if (function != null) {
            functionConfig.setName(function);
        }
        if (topicsToSerDeClassName != null) {
            functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        }
        if (outputTopic != null) {
            functionConfig.setOutput(outputTopic);
        }
        if (outputSerdeClassName != null) {
            functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        }
        if (className != null) {
            functionConfig.setClassName(className);
        }
        if (parallelism != null) {
            functionConfig.setParallelism(parallelism);
        }
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);

        resource.registerFunction(
                tenant,
                namespace,
                function,
                inputStream,
                details,
                functionPkgUrl,
                functionConfig,
                null);

    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function config is not provided")
    public void testMissingFunctionConfig() {
        resource.registerFunction(
                tenant,
                namespace,
                function,
                mockedInputStream,
                mockedFormData,
                null,
                null,
                null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function config is not provided")
    public void testUpdateMissingFunctionConfig() {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        resource.updateFunction(
                tenant,
                namespace,
                function,
                mockedInputStream,
                mockedFormData,
                null,
                null,
                null, null);
    }

    @Test
    public void testUpdateSourceWithNoChange() throws ClassNotFoundException {
        mockWorkerUtils();

        FunctionDetails functionDetails = createDefaultFunctionDetails();
        NarClassLoader mockedClassLoader = mock(NarClassLoader.class);
        mockStatic(FunctionCommon.class, ctx -> {
            ctx.when(() -> FunctionCommon.getFunctionTypes(any(FunctionConfig.class), any(Class.class))).thenReturn(new Class[]{String.class, String.class});
            ctx.when(() -> FunctionCommon.convertRuntime(any(FunctionConfig.Runtime.class))).thenCallRealMethod();
            ctx.when(() -> FunctionCommon.isFunctionCodeBuiltin(any())).thenReturn(true);
            ctx.when(() -> FunctionCommon.getClassLoaderFromPackage(any(),any(),any(),any())).thenCallRealMethod();
            ctx.when(FunctionCommon::createPkgTempFile).thenCallRealMethod();
        });

        doReturn(Function.class).when(mockedClassLoader).loadClass(anyString());

        FunctionsManager mockedFunctionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder()
                .classLoader(mockedClassLoader)
                .build();
        when(mockedFunctionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedFunctionsManager.getFunctionArchive(any())).thenReturn(getPulsarApiExamplesNar().toPath());

        when(mockedWorkerService.getFunctionsManager()).thenReturn(mockedFunctionsManager);
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        // No change on config,
        FunctionConfig funcConfig = createDefaultFunctionConfig();
        mockStatic(FunctionConfigUtils.class, ctx -> {
            ctx.when(() -> FunctionConfigUtils.convertFromDetails(any())).thenReturn(funcConfig);
            ctx.when(() -> FunctionConfigUtils.validateUpdate(any(), any())).thenCallRealMethod();
            ctx.when(() -> FunctionConfigUtils.convert(any(FunctionConfig.class), any(ClassLoader.class))).thenReturn(functionDetails);
            ctx.when(() -> FunctionConfigUtils.convert(any(FunctionConfig.class), any(FunctionConfigUtils.ExtractedFunctionDetails.class))).thenReturn(functionDetails);
            ctx.when(() -> FunctionConfigUtils.validateJavaFunction(any(), any())).thenCallRealMethod();
            ctx.when(() -> FunctionConfigUtils.doCommonChecks(any())).thenCallRealMethod();
            ctx.when(() -> FunctionConfigUtils.collectAllInputTopics(any())).thenCallRealMethod();
            ctx.when(() -> FunctionConfigUtils.doJavaChecks(any(), any())).thenCallRealMethod();
        });

        // config has not changes and don't update auth, should fail
        try {
            resource.updateFunction(
                    funcConfig.getTenant(),
                    funcConfig.getNamespace(),
                    funcConfig.getName(),
                    null,
                    mockedFormData,
                    null,
                    funcConfig,
                    null, null, null);
            fail("Update without changes should fail");
        } catch (RestException e) {
            assertTrue(e.getMessage().contains("Update contains no change"));
        }

        try {
            UpdateOptionsImpl updateOptions = new UpdateOptionsImpl();
            updateOptions.setUpdateAuthData(false);
            resource.updateFunction(
                    funcConfig.getTenant(),
                    funcConfig.getNamespace(),
                    funcConfig.getName(),
                    null,
                    mockedFormData,
                    null,
                    funcConfig,
                    null, null, updateOptions);
            fail("Update without changes should fail");
        } catch (RestException e) {
            assertTrue(e.getMessage().contains("Update contains no change"));
        }

        // no changes but set the auth-update flag to true, should not fail
        UpdateOptionsImpl updateOptions = new UpdateOptionsImpl();
        updateOptions.setUpdateAuthData(true);
        resource.updateFunction(
                funcConfig.getTenant(),
                funcConfig.getNamespace(),
                funcConfig.getName(),
                null,
                mockedFormData,
                null,
                funcConfig,
                null, null, updateOptions);
    }


    private void registerDefaultFunction() {
        registerDefaultFunctionWithPackageUrl(null);
    }

    private void registerDefaultFunctionWithPackageUrl(String packageUrl) {
        FunctionConfig functionConfig = createDefaultFunctionConfig();
        resource.registerFunction(
            tenant,
            namespace,
            function,
            mockedInputStream,
            mockedFormData,
            packageUrl,
            functionConfig,
                null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function test-function already exists")
    public void testRegisterExistedFunction() {
        try {
            Configurator.setRootLevel(Level.DEBUG);
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);
            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }


    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "upload failure")
    public void testRegisterFunctionUploadFailure() throws Exception {
        try {
            mockWorkerUtils(ctx -> {
                ctx.when(() -> {
                            WorkerUtils.uploadFileToBookkeeper(
                                    anyString(),
                                    any(File.class),
                                    any(Namespace.class));
                        }
                ).thenThrow(new IOException("upload failure"));
                ;
            });

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.INTERNAL_SERVER_ERROR);
            throw re;
        }
    }

    @Test
    public void testRegisterFunctionSuccess() throws Exception {
        try {
            mockWorkerUtils();

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(timeOut = 20000)
    public void testRegisterFunctionSuccessWithPackageName() {
        registerDefaultFunctionWithPackageUrl("function://public/default/test@v1");
    }

    @Test(timeOut = 20000)
    public void testRegisterFunctionFailedWithWrongPackageName() throws PulsarAdminException {
        try {
            doThrow(new PulsarAdminException("package name is invalid"))
                .when(mockedPackages).download(anyString(), anyString());
            registerDefaultFunctionWithPackageUrl("function://");
        } catch (RestException e) {
            // expected exception
            assertEquals(e.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace does not exist")
    public void testRegisterFunctionNonExistingNamespace() {
        try {
            this.namespaceList.clear();
            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant does not exist")
    public void testRegisterFunctionNonexistantTenant() throws Exception {
        try {
            when(mockedTenants.getTenantInfo(any())).thenThrow(PulsarAdminException.NotFoundException.class);
            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "function failed to register")
    public void testRegisterFunctionFailure() throws Exception {
        try {
            mockWorkerUtils();

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

            doThrow(new IllegalArgumentException("function failed to register"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function registration interrupted")
    public void testRegisterFunctionInterrupted() throws Exception {
        try {
            mockWorkerUtils();

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

            doThrow(new IllegalStateException("Function registration interrupted"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            registerDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.INTERNAL_SERVER_ERROR);
            throw re;
        }
    }

    /*
    Externally managed runtime,
    uploadBuiltinSinksSources == false
    Make sure uploadFileToBookkeeper is not called
    */
    @Test
    public void testRegisterFunctionSuccessK8sNoUpload() throws Exception {
        mockedWorkerService.getWorkerConfig().setUploadBuiltinSinksSources(false);

        mockStatic(WorkerUtils.class, ctx -> {
            ctx.when(() -> WorkerUtils.uploadFileToBookkeeper(
                    anyString(),
                    any(File.class),
                    any(Namespace.class)))
                    .thenThrow(new RuntimeException("uploadFileToBookkeeper triggered"));

        });

        NarClassLoader mockedClassLoader = mock(NarClassLoader.class);
        mockStatic(FunctionCommon.class, ctx -> {
            ctx.when(() -> FunctionCommon.getFunctionTypes(any(FunctionConfig.class), any(Class.class))).thenReturn(new Class[]{String.class, String.class});
            ctx.when(() -> FunctionCommon.convertRuntime(any(FunctionConfig.Runtime.class))).thenCallRealMethod();
            ctx.when(() -> FunctionCommon.isFunctionCodeBuiltin(any())).thenReturn(true);

        });

        doReturn(Function.class).when(mockedClassLoader).loadClass(anyString());

        FunctionsManager mockedFunctionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder()
                .classLoader(mockedClassLoader)
                .build();
        when(mockedFunctionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedFunctionsManager.getFunctionArchive(any())).thenReturn(getPulsarApiExamplesNar().toPath());

        when(mockedWorkerService.getFunctionsManager()).thenReturn(mockedFunctionsManager);

        when(mockedRuntimeFactory.externallyManaged()).thenReturn(true);
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

        FunctionConfig functionConfig = createDefaultFunctionConfig();
        functionConfig.setJar("builtin://exclamation");

        try (FileInputStream inputStream = new FileInputStream(getPulsarApiExamplesNar())) {
            resource.registerFunction(
                    tenant,
                    namespace,
                    function,
                    inputStream,
                    mockedFormData,
                    null,
                    functionConfig,
                    null);
        }
    }

    /*
    Externally managed runtime,
    uploadBuiltinSinksSources == true
    Make sure uploadFileToBookkeeper is called
    */
    @Test
    public void testRegisterFunctionSuccessK8sWithUpload() throws Exception {
        final String injectedErrMsg = "uploadFileToBookkeeper triggered";
        mockedWorkerService.getWorkerConfig().setUploadBuiltinSinksSources(true);

        mockStatic(WorkerUtils.class, ctx -> {
            ctx.when(() -> WorkerUtils.uploadFileToBookkeeper(
                    anyString(),
                    any(File.class),
                    any(Namespace.class)))
                    .thenThrow(new RuntimeException(injectedErrMsg));

        });

        NarClassLoader mockedClassLoader = mock(NarClassLoader.class);
        mockStatic(FunctionCommon.class, ctx -> {
            ctx.when(() -> FunctionCommon.getFunctionTypes(any(FunctionConfig.class), any(Class.class))).thenReturn(new Class[]{String.class, String.class});
            ctx.when(() -> FunctionCommon.convertRuntime(any(FunctionConfig.Runtime.class))).thenCallRealMethod();
            ctx.when(() -> FunctionCommon.isFunctionCodeBuiltin(any())).thenReturn(true);
        });

        doReturn(Function.class).when(mockedClassLoader).loadClass(anyString());

        FunctionsManager mockedFunctionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder()
                .classLoader(mockedClassLoader)
                .build();
        when(mockedFunctionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedFunctionsManager.getFunctionArchive(any())).thenReturn(getPulsarApiExamplesNar().toPath());

        when(mockedWorkerService.getFunctionsManager()).thenReturn(mockedFunctionsManager);

        when(mockedRuntimeFactory.externallyManaged()).thenReturn(true);
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

        FunctionConfig functionConfig = createDefaultFunctionConfig();
        functionConfig.setJar("builtin://exclamation");

        try (FileInputStream inputStream = new FileInputStream(getPulsarApiExamplesNar())) {
            try {
                resource.registerFunction(
                        tenant,
                        namespace,
                        function,
                        inputStream,
                        mockedFormData,
                        null,
                        functionConfig,
                        null);
                Assert.fail();
            } catch (RuntimeException e) {
                Assert.assertEquals(e.getMessage(), injectedErrMsg);
            }
        }
    }

    //
    // Update Functions
    //

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant is not provided")
    public void testUpdateFunctionMissingTenant() throws Exception {
        try {
            testUpdateFunctionMissingArguments(
                null,
                namespace,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    "Tenant is not provided");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace is not provided")
    public void testUpdateFunctionMissingNamespace() throws Exception {
        try {
            testUpdateFunctionMissingArguments(
                tenant,
                null,
                function,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    "Namespace is not provided");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function name is not provided")
    public void testUpdateFunctionMissingFunctionName() throws Exception {
        try {
            testUpdateFunctionMissingArguments(
                tenant,
                namespace,
                null,
                mockedInputStream,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    "Function name is not provided");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Update contains no change")
    public void testUpdateFunctionMissingPackage() throws Exception {
        try {
            mockWorkerUtils();
            testUpdateFunctionMissingArguments(
                tenant,
                namespace,
                function,
                null,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                className,
                parallelism,
                    "Update contains no change");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Update contains no change")
    public void testUpdateFunctionMissingInputTopic() throws Exception {
        try {
            mockWorkerUtils();

            testUpdateFunctionMissingArguments(
                    tenant,
                    namespace,
                    function,
                    null,
                    null,
                    mockedFormData,
                    outputTopic,
                    outputSerdeClassName,
                    className,
                    parallelism,
                    "Update contains no change");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Update contains no change")
    public void testUpdateFunctionMissingClassName() throws Exception {
        try {
            mockWorkerUtils();

            testUpdateFunctionMissingArguments(
                tenant,
                namespace,
                function,
                null,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                    outputSerdeClassName,
                null,
                parallelism,
                    "Update contains no change");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test
    public void testUpdateFunctionChangedParallelism() throws Exception {
        try {
            mockWorkerUtils();

            testUpdateFunctionMissingArguments(
                tenant,
                namespace,
                function,
                null,
                topicsToSerDeClassName,
                mockedFormData,
                outputTopic,
                outputSerdeClassName,
                null,
                parallelism + 1,
                null);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test
    public void testUpdateFunctionChangedInputs() throws Exception {
        mockWorkerUtils();

        testUpdateFunctionMissingArguments(
            tenant,
            namespace,
            function,
            null,
            topicsToSerDeClassName,
            mockedFormData,
            "DifferentOutput",
            outputSerdeClassName,
            null,
            parallelism,
            null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Input Topics cannot be altered")
    public void testUpdateFunctionChangedOutput() throws Exception {
        try {
            mockWorkerUtils();

            Map<String, String> someOtherInput = new HashMap<>();
            someOtherInput.put("DifferentTopic", TopicSchema.DEFAULT_SERDE);
            testUpdateFunctionMissingArguments(
                tenant,
                namespace,
                function,
                null,
                someOtherInput,
                mockedFormData,
                outputTopic,
                outputSerdeClassName,
                null,
                parallelism,
                "Input Topics cannot be altered");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    private void testUpdateFunctionMissingArguments(
            String tenant,
            String namespace,
            String function,
            InputStream inputStream,
            Map<String, String> topicsToSerDeClassName,
            FormDataContentDisposition details,
            String outputTopic,
            String outputSerdeClassName,
            String className,
            Integer parallelism,
            String expectedError) throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        FunctionConfig functionConfig = new FunctionConfig();
        if (tenant != null) {
            functionConfig.setTenant(tenant);
        }
        if (namespace != null) {
            functionConfig.setNamespace(namespace);
        }
        if (function != null) {
            functionConfig.setName(function);
        }
        if (topicsToSerDeClassName != null) {
            functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        }
        if (outputTopic != null) {
            functionConfig.setOutput(outputTopic);
        }
        if (outputSerdeClassName != null) {
            functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        }
        if (className != null) {
            functionConfig.setClassName(className);
        }
        if (parallelism != null) {
            functionConfig.setParallelism(parallelism);
        }
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);

        if (expectedError != null) {
            doThrow(new IllegalArgumentException(expectedError))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());
        }

        resource.updateFunction(
            tenant,
            namespace,
            function,
            inputStream,
            details,
            null,
            functionConfig,
                null, null);

    }

    private void updateDefaultFunction() {
        updateDefaultFunctionWithPackageUrl(null);
    }

    private void updateDefaultFunctionWithPackageUrl(String packageUrl) {
        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);

        resource.updateFunction(
            tenant,
            namespace,
            function,
            mockedInputStream,
            mockedFormData,
            packageUrl,
            functionConfig,
                null, null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function test-function doesn't exist")
    public void testUpdateNotExistedFunction() {
        try {
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);
            updateDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "upload failure")
    public void testUpdateFunctionUploadFailure() throws Exception {
        try {
            mockWorkerUtils(ctx -> {
                ctx.when(() -> {
                    WorkerUtils.uploadFileToBookkeeper(
                            anyString(),
                            any(File.class),
                            any(Namespace.class));

                }).thenThrow(new IOException("upload failure"));
                ctx.when(() -> WorkerUtils.dumpToTmpFile(any())).thenCallRealMethod();
            });

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

            updateDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.INTERNAL_SERVER_ERROR);
            throw re;
        }
    }

    @Test
    public void testUpdateFunctionSuccess() throws Exception {
        mockWorkerUtils();

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        updateDefaultFunction();
    }

    @Test
    public void testUpdateFunctionWithUrl() {
        Configurator.setRootLevel(Level.DEBUG);

        String fileLocation = FutureUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        String filePackageUrl = "file://" + fileLocation;

        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        resource.updateFunction(
            tenant,
            namespace,
            function,
            null,
            null,
            filePackageUrl,
            functionConfig,
                null, null);

    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "function failed to register")
    public void testUpdateFunctionFailure() throws Exception {
        try {
            mockWorkerUtils();

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

            doThrow(new IllegalArgumentException("function failed to register"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            updateDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function registeration interrupted")
    public void testUpdateFunctionInterrupted() throws Exception {
        try {
            mockWorkerUtils();

            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

            doThrow(new IllegalStateException("Function registeration interrupted"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            updateDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.INTERNAL_SERVER_ERROR);
            throw re;
        }
    }


    @Test(timeOut = 20000)
    public void testUpdateFunctionSuccessWithPackageName() {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);
        updateDefaultFunctionWithPackageUrl("function://public/default/test@v1");
    }

    @Test(timeOut = 20000)
    public void testUpdateFunctionFailedWithWrongPackageName() throws PulsarAdminException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);
        try {
            doThrow(new PulsarAdminException("package name is invalid"))
                .when(mockedPackages).download(anyString(), anyString());
            registerDefaultFunctionWithPackageUrl("function://");
        } catch (RestException e) {
            // expected exception
            assertEquals(e.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
        }
    }

    //
    // deregister function
    //

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant is not provided")
    public void testDeregisterFunctionMissingTenant() {
        try {

            testDeregisterFunctionMissingArguments(
                null,
                namespace,
                function
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace is not provided")
    public void testDeregisterFunctionMissingNamespace() {
        try {
            testDeregisterFunctionMissingArguments(
                tenant,
                null,
                function
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function name is not provided")
    public void testDeregisterFunctionMissingFunctionName() {
        try {
             testDeregisterFunctionMissingArguments(
                tenant,
                namespace,
                null
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    private void testDeregisterFunctionMissingArguments(
            String tenant,
            String namespace,
            String function
    ) {
        resource.deregisterFunction(
            tenant,
            namespace,
            function,
                null);
    }

    private void deregisterDefaultFunction() {
        resource.deregisterFunction(
            tenant,
            namespace,
            function,
                null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function test-function doesn't exist")
    public void testDeregisterNotExistedFunction() {
        try {
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);
            deregisterDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.NOT_FOUND);
            throw re;
        }
    }

    @Test
    public void testDeregisterFunctionSuccess() {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        deregisterDefaultFunction();
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "function failed to deregister")
    public void testDeregisterFunctionFailure() throws Exception {
        try {
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

            doThrow(new IllegalArgumentException("function failed to deregister"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            deregisterDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function deregisteration interrupted")
    public void testDeregisterFunctionInterrupted() throws Exception {
        try {
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

            doThrow(new IllegalStateException("Function deregisteration interrupted"))
                    .when(mockedManager).updateFunctionOnLeader(any(FunctionMetaData.class), Mockito.anyBoolean());

            deregisterDefaultFunction();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.INTERNAL_SERVER_ERROR);
            throw re;
        }
    }

    //
    // Get Function Info
    //

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant is not provided")
    public void testGetFunctionMissingTenant() {
        try {
            testGetFunctionMissingArguments(
                null,
                namespace,
                function
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace is not provided")
    public void testGetFunctionMissingNamespace() {
        try {
            testGetFunctionMissingArguments(
                tenant,
                null,
                function
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function name is not provided")
    public void testGetFunctionMissingFunctionName() {
        try {
            testGetFunctionMissingArguments(
                tenant,
                namespace,
                null
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    private void testGetFunctionMissingArguments(
            String tenant,
            String namespace,
            String function
    ) {
        resource.getFunctionInfo(
            tenant,
            namespace,
            function,null
        );

    }

    private FunctionConfig getDefaultFunctionInfo() {
        return resource.getFunctionInfo(
            tenant,
            namespace,
            function,
                null
        );
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function test-function doesn't exist")
    public void testGetNotExistedFunction() {
        try {
            when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);
            getDefaultFunctionInfo();
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.NOT_FOUND);
            throw re;
        }
    }

    @Test
    public void testGetFunctionSuccess() {
        mockInstanceUtils();
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        SinkSpec sinkSpec = SinkSpec.newBuilder()
                .setTopic(outputTopic)
                .setSerDeClassName(outputSerdeClassName).build();
        FunctionDetails functionDetails = FunctionDetails.newBuilder()
                .setClassName(className)
                .setSink(sinkSpec)
                .setAutoAck(true)
                .setName(function)
                .setNamespace(namespace)
                .setProcessingGuarantees(ProcessingGuarantees.ATMOST_ONCE)
                .setTenant(tenant)
                .setParallelism(parallelism)
                .setSource(SourceSpec.newBuilder().setSubscriptionType(subscriptionType)
                        .putAllTopicsToSerDeClassName(topicsToSerDeClassName)).build();
        FunctionMetaData metaData = FunctionMetaData.newBuilder()
            .setCreateTime(System.currentTimeMillis())
            .setFunctionDetails(functionDetails)
            .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("/path/to/package"))
            .setVersion(1234)
            .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(function))).thenReturn(metaData);

        FunctionConfig functionConfig = getDefaultFunctionInfo();
        assertEquals(
                FunctionConfigUtils.convertFromDetails(functionDetails),
                functionConfig);
    }

    //
    // List Functions
    //

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Tenant is not provided")
    public void testListFunctionsMissingTenant() {
        try {
            testListFunctionsMissingArguments(
                null,
                namespace
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Namespace is not provided")
    public void testListFunctionsMissingNamespace() {
        try {
            testListFunctionsMissingArguments(
                tenant,
                null
            );
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatusInfo(), Response.Status.BAD_REQUEST);
            throw re;
        }
    }

    private void testListFunctionsMissingArguments(
            String tenant,
            String namespace
    ) {
        resource.listFunctions(
            tenant,
            namespace,null
        );

    }

    private List<String> listDefaultFunctions() {
        return resource.listFunctions(
            tenant,
            namespace,null
        );
    }

    @Test
    public void testListFunctionsSuccess() {
        mockInstanceUtils();
        final List<String> functions = Lists.newArrayList("test-1", "test-2");
        final List<FunctionMetaData> metaDataList = new LinkedList<>();
        FunctionMetaData functionMetaData1 = FunctionMetaData.newBuilder().setFunctionDetails(
                FunctionDetails.newBuilder().setName("test-1").build()
        ).build();
        FunctionMetaData functionMetaData2 = FunctionMetaData.newBuilder().setFunctionDetails(
                FunctionDetails.newBuilder().setName("test-2").build()
        ).build();
        metaDataList.add(functionMetaData1);
        metaDataList.add(functionMetaData2);
        when(mockedManager.listFunctions(eq(tenant), eq(namespace))).thenReturn(metaDataList);

        List<String> functionList = listDefaultFunctions();
        assertEquals(functions, functionList);
    }

    @Test
    public void testOnlyGetSources() {
        List<String> functions = Lists.newArrayList("test-2");
        List<FunctionMetaData> functionMetaDataList = new LinkedList<>();
        FunctionMetaData f1 = FunctionMetaData.newBuilder().setFunctionDetails(
                FunctionDetails.newBuilder()
                        .setName("test-1")
                        .setComponentType(FunctionDetails.ComponentType.SOURCE)
                        .build()).build();
        functionMetaDataList.add(f1);
        FunctionMetaData f2 = FunctionMetaData.newBuilder().setFunctionDetails(
                FunctionDetails.newBuilder()
                        .setName("test-2")
                        .setComponentType(FunctionDetails.ComponentType.FUNCTION)
                        .build()).build();
        functionMetaDataList.add(f2);
        FunctionMetaData f3 = FunctionMetaData.newBuilder().setFunctionDetails(
                FunctionDetails.newBuilder()
                        .setName("test-3")
                        .setComponentType(FunctionDetails.ComponentType.SINK)
                        .build()).build();
        functionMetaDataList.add(f3);
        when(mockedManager.listFunctions(eq(tenant), eq(namespace))).thenReturn(functionMetaDataList);

        List<String> functionList = listDefaultFunctions();
        assertEquals(functions, functionList);
    }

    @Test
    public void testDownloadFunctionHttpUrl() throws Exception {
        String jarHttpUrl =
                "https://repo1.maven.org/maven2/org/apache/pulsar/pulsar-common/2.4.2/pulsar-common-2.4.2.jar";
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        StreamingOutput streamOutput = resource.downloadFunction(jarHttpUrl, null);
        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        Assert.assertTrue(pkgFile.exists());
        pkgFile.delete();
    }

    @Test
    public void testDownloadFunctionFile() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String fileLocation = file.getAbsolutePath().replace('\\', '/');
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        StreamingOutput streamOutput = resource.downloadFunction("file:///" + fileLocation, null);
        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }

    @Test
    public void testDownloadFunctionBuiltinConnector() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        WorkerConfig config = new WorkerConfig()
            .setUploadBuiltinSinksSources(false);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(config);

        Connector connector = Connector.builder().archivePath(file.toPath()).build();
        ConnectorsManager connectorsManager = mock(ConnectorsManager.class);
        when(connectorsManager.getConnector("cassandra")).thenReturn(connector);
        when(mockedWorkerService.getConnectorsManager()).thenReturn(connectorsManager);

        StreamingOutput streamOutput = resource.downloadFunction("builtin://cassandra", null);

        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        output.flush();
        output.close();
        Assert.assertTrue(pkgFile.exists());
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }

    @Test
    public void testDownloadFunctionBuiltinFunction() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        WorkerConfig config = new WorkerConfig()
            .setUploadBuiltinSinksSources(false);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(config);

        FunctionsManager functionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder().archivePath(file.toPath()).build();
        when(functionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedWorkerService.getConnectorsManager()).thenReturn(mock(ConnectorsManager.class));
        when(mockedWorkerService.getFunctionsManager()).thenReturn(functionsManager);

        StreamingOutput streamOutput = resource.downloadFunction("builtin://exclamation", null);

        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        output.flush();
        output.close();
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }

    @Test
    public void testDownloadFunctionBuiltinConnectorByName() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        WorkerConfig config = new WorkerConfig()
            .setUploadBuiltinSinksSources(false);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(config);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        FunctionMetaData metaData = FunctionMetaData.newBuilder()
                .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("builtin://cassandra"))
                .setTransformFunctionPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("http://invalid"))
                .setFunctionDetails(FunctionDetails.newBuilder().setComponentType(FunctionDetails.ComponentType.SINK))
                .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(function))).thenReturn(metaData);

        Connector connector = Connector.builder().archivePath(file.toPath()).build();
        ConnectorsManager connectorsManager = mock(ConnectorsManager.class);
        when(connectorsManager.getConnector("cassandra")).thenReturn(connector);
        when(mockedWorkerService.getConnectorsManager()).thenReturn(connectorsManager);

        StreamingOutput streamOutput = resource.downloadFunction(tenant, namespace, function,
                AuthenticationParameters.builder().build(), false);
        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }

    @Test
    public void testDownloadFunctionBuiltinFunctionByName() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        WorkerConfig config = new WorkerConfig()
            .setUploadBuiltinSinksSources(false);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(config);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        FunctionMetaData metaData = FunctionMetaData.newBuilder()
            .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("builtin://exclamation"))
            .setTransformFunctionPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("http://invalid"))
            .setFunctionDetails(FunctionDetails.newBuilder().setComponentType(FunctionDetails.ComponentType.FUNCTION))
            .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(function))).thenReturn(metaData);

        FunctionsManager functionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder().archivePath(file.toPath()).build();
        when(functionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedWorkerService.getConnectorsManager()).thenReturn(mock(ConnectorsManager.class));
        when(mockedWorkerService.getFunctionsManager()).thenReturn(functionsManager);

        StreamingOutput streamOutput = resource.downloadFunction(tenant, namespace, function,
                AuthenticationParameters.builder().build(), false);
        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }

    @Test
    public void testDownloadTransformFunctionByName() throws Exception {
        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String testDir = FunctionApiV3ResourceTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        WorkerConfig workerConfig = new WorkerConfig()
            .setUploadBuiltinSinksSources(false);
        when(mockedWorkerService.getWorkerConfig()).thenReturn(workerConfig);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        FunctionMetaData metaData = FunctionMetaData.newBuilder()
                .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("http://invalid"))
                .setTransformFunctionPackageLocation(PackageLocationMetaData.newBuilder()
                        .setPackagePath("builtin://exclamation"))
                .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(function))).thenReturn(metaData);

        FunctionsManager functionsManager = mock(FunctionsManager.class);
        FunctionArchive functionArchive = FunctionArchive.builder().archivePath(file.toPath()).build();
        when(functionsManager.getFunction("exclamation")).thenReturn(functionArchive);
        when(mockedWorkerService.getConnectorsManager()).thenReturn(mock(ConnectorsManager.class));
        when(mockedWorkerService.getFunctionsManager()).thenReturn(functionsManager);

        StreamingOutput streamOutput = resource.downloadFunction(tenant, namespace, function,
                AuthenticationParameters.builder().build(), true);
        File pkgFile = new File(testDir, UUID.randomUUID().toString());
        OutputStream output = new FileOutputStream(pkgFile);
        streamOutput.write(output);
        Assert.assertTrue(pkgFile.exists());
        Assert.assertEquals(file.length(), pkgFile.length());
        pkgFile.delete();
    }


    @Test
    public void testRegisterFunctionFileUrlWithValidSinkClass() throws Exception {
        Configurator.setRootLevel(Level.DEBUG);

        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String fileLocation = file.getAbsolutePath().replace('\\', '/');
        String filePackageUrl = "file:///" + fileLocation;
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        resource.registerFunction(tenant, namespace, function, null, null, filePackageUrl, functionConfig, null);

    }

    @Test
    public void testRegisterFunctionWithConflictingFields() throws Exception {
        Configurator.setRootLevel(Level.DEBUG);
        String actualTenant = "DIFFERENT_TENANT";
        String actualNamespace = "DIFFERENT_NAMESPACE";
        String actualName = "DIFFERENT_NAME";
        this.namespaceList.add(actualTenant + "/" + actualNamespace);

        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String fileLocation = file.getAbsolutePath().replace('\\', '/');
        String filePackageUrl = "file:///" + fileLocation;
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);
        when(mockedManager.containsFunction(eq(actualTenant), eq(actualNamespace), eq(actualName))).thenReturn(false);

        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        resource.registerFunction(actualTenant, actualNamespace, actualName, null, null, filePackageUrl, functionConfig,
                null);
    }

    @Test(expectedExceptions = RestException.class, expectedExceptionsMessageRegExp = "Function language runtime is either not set or cannot be determined")
    public void testCreateFunctionWithoutSettingRuntime() throws Exception {
        Configurator.setRootLevel(Level.DEBUG);

        URL fileUrl = getClass().getClassLoader().getResource("test_worker_config.yml");
        File file = Paths.get(fileUrl.toURI()).toFile();
        String fileLocation = file.getAbsolutePath().replace('\\', '/');
        String filePackageUrl = "file:///" + fileLocation;
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(false);

        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        resource.registerFunction(tenant, namespace, function, null, null, filePackageUrl, functionConfig, null);

    }

    public static FunctionConfig createDefaultFunctionConfig() {
        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(tenant);
        functionConfig.setNamespace(namespace);
        functionConfig.setName(function);
        functionConfig.setClassName(className);
        functionConfig.setParallelism(parallelism);
        functionConfig.setCustomSerdeInputs(topicsToSerDeClassName);
        functionConfig.setOutput(outputTopic);
        functionConfig.setOutputSerdeClassName(outputSerdeClassName);
        functionConfig.setRuntime(FunctionConfig.Runtime.JAVA);
        return functionConfig;
    }

    public static FunctionDetails createDefaultFunctionDetails() {
        FunctionConfig functionConfig = createDefaultFunctionConfig();
        return FunctionConfigUtils.convert(functionConfig, (ClassLoader) null);
    }
}
