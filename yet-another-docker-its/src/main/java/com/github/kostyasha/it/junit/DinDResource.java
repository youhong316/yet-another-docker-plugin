package com.github.kostyasha.it.junit;

import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.NotFoundException;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.model.BuildResponseItem;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.model.ExposedPort;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.model.PortBinding;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.api.model.VolumesFrom;
import com.github.kostyasha.yad.docker_java.com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.kostyasha.yad.docker_java.org.apache.commons.io.FileUtils;
import com.github.kostyasha.yad.docker_java.org.apache.commons.lang.StringUtils;
import com.github.kostyasha.yad.other.VariableSSLConfig;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.github.kostyasha.it.utils.DockerHPIContainerUtil.getResource;
import static java.util.Objects.nonNull;

/**
 * Run DinD container with data container built from Dockerfile with keys for DinD.
 * TODO extract key generator.
 *
 * @author Kanstantsin Shautsou
 */
public class DinDResource extends ExternalResource {
    private static final Logger LOG = LoggerFactory.getLogger(DinDResource.class);

    public static final String DATA_IMAGE_TAG = DinDResource.class.getSimpleName().toLowerCase();
    public static final String DATA_CONTAINER_NAME = DinDResource.class.getName() + "_data";
    public static final String HOST_IMAGE_NAME = "dind_fedora";
    public static final String HOST_CONTAINER_NAME = DinDResource.class.getName() + "_host";
    public static int CONTAINER_PORT = 44444;


    private final DockerRule d;
    private final TemporaryFolder folder;

    public DinDResource(DockerRule d, TemporaryFolder folder) {
        this.d = d;
        this.folder = folder;
    }

    private String dataContainerId;
    private String hostContainerId;

    public String getDataContainerId() {
        return dataContainerId;
    }

    public String getHostContainerId() {
        return hostContainerId;
    }

    /**
     * Client keys for DinD docker daemon.
     *
     * @return client keys contained in this class resources.
     */
    public VariableSSLConfig getVariableSSLConfig() throws IOException {
        return new VariableSSLConfig(
                getResource(getClass(), "data_container/keys/key.pem"),
                getResource(getClass(), "data_container/keys/cert.pem"),
                getResource(getClass(), "data_container/keys/ca.pem")
        );
    }

    @Override
    protected void before() throws Throwable {
        // remove host container
        try {
            d.getDockerCli().removeContainerCmd(HOST_CONTAINER_NAME)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            LOG.info("Removed container {}", HOST_CONTAINER_NAME);
        } catch (NotFoundException ignore) {
        }
        // remove data container
        try {
            d.getDockerCli().removeContainerCmd(DATA_CONTAINER_NAME)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            LOG.info("Removed container {}", DATA_CONTAINER_NAME);
        } catch (NotFoundException ignore) {
        }

        // remove data image
        try {
            d.getDockerCli().removeImageCmd(DATA_IMAGE_TAG)
                    .withForce(true)
                    .exec();
            LOG.info("Removed image {}", DATA_IMAGE_TAG);
        } catch (NotFoundException ignore) {
        }

        final File buildDir = folder.newFolder(getClass().getName());

        File resources = new File("src/main/resources/" + getClass().getName().replace(".", "/") + "/data_container");
        FileUtils.copyDirectory(resources, buildDir);

        final String imageId = d.getDockerCli().buildImageCmd(buildDir)
                .withForcerm()
                .withTag(DATA_IMAGE_TAG)
                .exec(new BuildImageResultCallback() {
                    public void onNext(BuildResponseItem item) {
                        String text = item.getStream();
                        if (nonNull(text)) {
                            LOG.debug(StringUtils.removeEnd(text, DockerRule.NL));
                        }
                        super.onNext(item);
                    }
                })
                .awaitImageId();

        dataContainerId = d.getDockerCli().createContainerCmd(imageId)
                .withCmd("/bin/true")
                .withName(DATA_CONTAINER_NAME)
                .exec()
                .getId();

        hostContainerId = d.getDockerCli().createContainerCmd(HOST_IMAGE_NAME)
                .withName(HOST_CONTAINER_NAME)
                .withPrivileged(true)
                .withEnv("DOCKER_DAEMON_ARGS=" +
                                "--tlsverify " +
                                "--tlscacert=/var/keys/ca.pem " +
                                "--tlscert=/var/keys/server-cert.pem " +
                                "--tlskey=/var/keys/server-key.pem",
                        "PORT=" + CONTAINER_PORT)
                .withExposedPorts(new ExposedPort(CONTAINER_PORT))
                .withPortBindings(PortBinding.parse("0.0.0.0:" + CONTAINER_PORT + ":" + CONTAINER_PORT))
                .withPortSpecs(String.format("%d/tcp", CONTAINER_PORT))
                .withVolumesFrom(new VolumesFrom(dataContainerId))
                .exec()
                .getId();

        d.getDockerCli().startContainerCmd(hostContainerId).exec();
    }
}
