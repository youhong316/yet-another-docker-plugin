package com.github.kostyasha.it.tests;

import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.kostyasha.it.other.BCallable;
import com.github.kostyasha.it.other.TestCause;
import com.github.kostyasha.it.rule.DockerRule;
import com.github.kostyasha.yad.DockerCloud;
import com.github.kostyasha.yad.DockerConnector;
import com.github.kostyasha.yad.DockerContainerLifecycle;
import com.github.kostyasha.yad.DockerSlaveTemplate;
import com.github.kostyasha.yad.commons.DockerPullImage;
import com.github.kostyasha.yad.commons.DockerRemoveContainer;
import com.github.kostyasha.yad.launcher.DockerComputerJNLPLauncher;
import com.github.kostyasha.yad.strategy.DockerOnceRetentionStrategy;
import hudson.cli.DockerCLI;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.labels.LabelAtom;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.EnvironmentVariablesNodeProperty.Entry;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.tasks.Shell;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerCredentials;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.github.kostyasha.it.utils.JenkinsRuleHelpers.caller;
import static com.github.kostyasha.it.utils.JenkinsRuleHelpers.waitUntilNoActivityUpTo;
import static com.github.kostyasha.yad.commons.DockerImagePullStrategy.PULL_LATEST;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.jvnet.hudson.test.JenkinsRule.getLog;
import static org.mockito.Matchers.isNull;

/**
 * @author Kanstantsin Shautsou
 */
public class SimpleBuildTest implements Serializable {
    public static final Logger LOG = LoggerFactory.getLogger(SimpleBuildTest.class);
    private static final long serialVersionUID = 1L;
    private static final String DOCKER_CLOUD_LABEL = "docker-label";
    private static final String DOCKER_CLOUD_NAME = "docker-cloud";
    private static final String TEST_VALUE = "2323re23e";

    //TODO redesign rule internals
    @ClassRule
    public static DockerRule d = new DockerRule(false);

    @Rule
    public MyResource dJenkins = new MyResource();

    public static class MyResource extends ExternalResource {
        public String jenkinsId;
        public DockerCLI cli;

        public void call(BCallable callable) throws Throwable {
            caller(cli, callable);
        }

        @Override
        protected void before() throws Throwable {
            jenkinsId = d.runFreshJenkinsContainer(PULL_LATEST, false);
            cli = d.createCliForContainer(jenkinsId);

            caller(cli, new PrepareCloudCallable(
                    cli.jenkins.getPort(),
                    d.getDockerServerCredentials(),
                    d.clientConfig.getUri(),
                    DockerRule.SLAVE_IMAGE_JNLP
            ));
        }

        @Override
        protected void after() {
            try {
                cli.close();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static class PrepareCloudCallable extends BCallable {
        private final int jenkinsPort;
        private final DockerServerCredentials dockerServerCredentials;
        private final URI dockerUri;
        private final String slaveImage;

        public PrepareCloudCallable(int jenkinsPort, DockerServerCredentials credentials, URI dockerUri, String slaveImage) {
            this.jenkinsPort = jenkinsPort;
            this.dockerServerCredentials = credentials;
            this.dockerUri = dockerUri;
            this.slaveImage = slaveImage;
        }

        @Override
        public Boolean call() throws Throwable {
            final Jenkins jenkins = Jenkins.getActiveInstance();

            // prepare jenkins global (url, cred)
            JenkinsLocationConfiguration.get().setUrl(String.format("http://%s:%d", dockerUri.getHost(), jenkinsPort));

            SystemCredentialsProvider.getInstance().getCredentials().add(dockerServerCredentials);

            // prepare Docker Cloud
            final DockerConnector dockerConnector = new DockerConnector(
                    String.format("https://%s:%d", dockerUri.getHost(), dockerUri.getPort()));
            dockerConnector.setCredentialsId(dockerServerCredentials.getId());
            dockerConnector.setConnectTimeout(10);
            dockerConnector.testConnection();

            //launcher
            final DockerComputerJNLPLauncher launcher = new DockerComputerJNLPLauncher(new JNLPLauncher());
            final DockerPullImage pullImage = new DockerPullImage();
            pullImage.setPullStrategy(PULL_LATEST);

            //remove
            final DockerRemoveContainer removeContainer = new DockerRemoveContainer();
            removeContainer.setRemoveVolumes(true);
            removeContainer.setForce(true);

            //lifecycle
            final DockerContainerLifecycle containerLifecycle = new DockerContainerLifecycle();
            containerLifecycle.setImage(slaveImage);
            containerLifecycle.setPullImage(pullImage);
            containerLifecycle.setRemoveContainer(removeContainer);

            //template
            final Entry entry = new Entry("super-key", TEST_VALUE);
            final EnvironmentVariablesNodeProperty nodeProperty = new EnvironmentVariablesNodeProperty(entry);
            final ArrayList<NodeProperty<?>> nodeProperties = new ArrayList<>();
            nodeProperties.add(nodeProperty);

            final DockerSlaveTemplate slaveTemplate = new DockerSlaveTemplate();
            slaveTemplate.setLabelString(DOCKER_CLOUD_LABEL);
            slaveTemplate.setLauncher(launcher);
            slaveTemplate.setMode(Node.Mode.EXCLUSIVE);
            slaveTemplate.setRetentionStrategy(new DockerOnceRetentionStrategy(10));
            slaveTemplate.setDockerContainerLifecycle(containerLifecycle);
            slaveTemplate.setNodeProperties(nodeProperties);

            final List<DockerSlaveTemplate> templates = new ArrayList<>();
            templates.add(slaveTemplate);

            final DockerCloud dockerCloud = new DockerCloud(
                    DOCKER_CLOUD_NAME,
                    templates,
                    3,
                    dockerConnector
            );

            jenkins.clouds.add(dockerCloud);
            jenkins.save(); // either xmls a half broken

            return true;
        }
    }

    @Test
    public void freestyleProjectBuilds() throws Throwable {
        dJenkins.call(new FreestyleProjectBuildCallable());
    }

    private static class FreestyleProjectBuildCallable extends BCallable {
        @Override
        public Boolean call() throws Throwable {
            final Jenkins jenkins = Jenkins.getActiveInstance();

            // prepare job
            final FreeStyleProject project = jenkins.createProject(FreeStyleProject.class, "freestyle-project");
            final Shell env = new Shell("env");
            project.getBuildersList().add(env);
            project.setAssignedLabel(new LabelAtom(DOCKER_CLOUD_LABEL));
            project.save();

            // test
            project.scheduleBuild(new TestCause());

            waitUntilNoActivityUpTo(jenkins, 60 * 1000);

            final FreeStyleBuild lastBuild = project.getLastBuild();
            assertThat(lastBuild, not(isNull()));
            assertThat(lastBuild.getResult(), is(Result.SUCCESS));

            assertThat(getLog(lastBuild), Matchers.containsString(TEST_VALUE));

            return true;
        }
    }
}
