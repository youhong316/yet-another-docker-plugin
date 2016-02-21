package com.github.kostyasha.it.tests;

import com.github.kostyasha.it.junit.DinDResource;
import com.github.kostyasha.it.junit.DockerRule;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.DockerClient;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.model.Version;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.core.DockerClientBuilder;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.core.DockerClientConfig;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.jaxrs.DockerCmdExecFactoryImpl;
import com.github.kostyasha.yad.other.VariableSSLConfig;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.github.kostyasha.it.junit.DockerRule.getDockerItDir;
import static com.github.kostyasha.it.utils.DockerHPIContainerUtil.getResource;
import static com.github.kostyasha.it.utils.DockerUtils.getExposedPort;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * @author Kanstantsin Shautsou
 */
public class ShortTLSKeyTest {
    private static final Logger LOG = LoggerFactory.getLogger(ShortTLSKeyTest.class);

    public static TemporaryFolder folder = new TemporaryFolder(new File(getDockerItDir()));
    public static DockerRule d = new DockerRule(false);
    public static DinDResource container = new DinDResource(d, folder);

    @ClassRule
    public static RuleChain chain = RuleChain.outerRule(d).around(folder).around(container);

    @Test
    public void testKey() throws IOException {
        final InspectContainerResponse inspect = d.getDockerCli().inspectContainerCmd(container.getHostContainerId())
                .exec();
        assertThat(inspect.getState().isRunning(), is(true));
        final int exposedPort = getExposedPort(inspect, DinDResource.CONTAINER_PORT);
        LOG.info("Exposed port {}", exposedPort);

        final VariableSSLConfig sslConfig = container.getVariableSSLConfig();

        DockerClientConfig clientConfig = new DockerClientConfig.DockerClientConfigBuilder()
                .withUri("https://" + d.getHost() + ":" + DinDResource.CONTAINER_PORT)
                .withSSLConfig(sslConfig)
                .build();

        DockerCmdExecFactoryImpl dockerCmdExecFactory = new DockerCmdExecFactoryImpl()
                .withReadTimeout(0)
                .withConnectTimeout(10000);

        DockerClient dockerClient = DockerClientBuilder.getInstance(clientConfig)
                .withDockerCmdExecFactory(dockerCmdExecFactory)
                .build();

        final Version version = dockerClient.versionCmd().exec();
        LOG.info("Daemon version {}", version);
        assertThat(version.getVersion(), notNullValue());
    }
}
