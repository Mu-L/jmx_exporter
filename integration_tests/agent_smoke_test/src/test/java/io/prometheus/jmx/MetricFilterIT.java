package io.prometheus.jmx;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;

public class MetricFilterIT {

    private Volume volume;
    private GenericContainer<?> javaContainer;
    private Scraper scraper;

    @Before
    public void setUp() throws IOException, URISyntaxException {
        volume = Volume.create("metric-filter-integration-test-");
        volume.copyAgentJar("jmx_prometheus_javaagent");
        volume.copyConfigYaml("config.yml");
        volume.copyExampleApplication();
        String cmd = "java -javaagent:agent.jar=9000:config.yaml -jar jmx_example_application.jar";
        javaContainer = new GenericContainer<>("openjdk:11-jre")
                .withFileSystemBind(volume.getHostPath(), "/app", BindMode.READ_ONLY)
                .withWorkingDirectory("/app")
                .withExposedPorts(9000)
                .withCommand(cmd)
                .waitingFor(Wait.forLogMessage(".*registered.*", 1))
                .withLogConsumer(System.out::print);
        javaContainer.start();
        scraper = new Scraper(javaContainer.getHost(), javaContainer.getMappedPort(9000));
    }

    @After
    public void tearDown() throws IOException {
        javaContainer.stop();
        volume.close();
    }

    @Test
    public void testMetricFilter() throws IOException, URISyntaxException {
        String deadlocked = "jvm_threads_deadlocked ";
        String deadlockedMonitor = "jvm_threads_deadlocked_monitor ";
        List<String> metrics = scraper.scrape(SECONDS.toMillis(10));
        Optional<String> metric;

        // config.yml -> all metrics should exist
        metric = metrics.stream().filter(line -> line.startsWith(deadlocked)).findAny();
        Assert.assertTrue(deadlocked + "should exist", metric.isPresent());
        metric = metrics.stream().filter(line -> line.startsWith(deadlockedMonitor)).findAny();
        Assert.assertTrue(deadlockedMonitor + "should exist", metric.isPresent());

        // config-metric-filter-1.yml -> jvm_threads_deadlocked should be filtered
        volume.copyConfigYaml("config-metric-filter-1.yml");
        metrics = scraper.scrape(SECONDS.toMillis(10));
        metric = metrics.stream().filter(line -> line.startsWith(deadlocked)).findAny();
        Assert.assertFalse(deadlocked + "should be filtered out", metric.isPresent());
        metric = metrics.stream().filter(line -> line.startsWith(deadlockedMonitor)).findAny();
        Assert.assertTrue(deadlockedMonitor + "should exist", metric.isPresent());

        // config-metric-filter-2.yml -> all metrics starting with jvm_threads_deadlocked should be filtered
        volume.copyConfigYaml("config-metric-filter-2.yml");
        metrics = scraper.scrape(SECONDS.toMillis(10));
        metric = metrics.stream().filter(line -> line.startsWith(deadlocked)).findAny();
        Assert.assertFalse(deadlocked + "should be filtered out", metric.isPresent());
        metric = metrics.stream().filter(line -> line.startsWith(deadlockedMonitor)).findAny();
        Assert.assertFalse(deadlockedMonitor + "should be filtered out", metric.isPresent());
    }
}
