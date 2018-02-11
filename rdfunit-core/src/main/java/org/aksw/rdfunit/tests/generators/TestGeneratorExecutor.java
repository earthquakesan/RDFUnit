package org.aksw.rdfunit.tests.generators;

import lombok.extern.slf4j.Slf4j;
import org.aksw.rdfunit.enums.TestGenerationType;
import org.aksw.rdfunit.io.reader.RdfReaderException;
import org.aksw.rdfunit.io.reader.RdfStreamReader;
import org.aksw.rdfunit.io.writer.RdfFileWriter;
import org.aksw.rdfunit.model.interfaces.TestCase;
import org.aksw.rdfunit.model.interfaces.TestGenerator;
import org.aksw.rdfunit.model.interfaces.TestSuite;
import org.aksw.rdfunit.model.readers.BatchTestCaseReader;
import org.aksw.rdfunit.sources.CacheUtils;
import org.aksw.rdfunit.sources.SchemaSource;
import org.aksw.rdfunit.sources.Source;
import org.aksw.rdfunit.sources.TestSource;
import org.aksw.rdfunit.tests.generators.monitors.TestGeneratorExecutorMonitor;
import org.aksw.rdfunit.utils.TestUtils;

import java.util.ArrayList;
import java.util.Collection;

import static com.google.common.base.Preconditions.checkArgument;


/**
 * <p>TestGeneratorExecutor class.</p>
 *
 * @author Dimitris Kontokostas
 *         handles test generation form a schema or a cache
 * @since 11/20/13 7:31 PM
 * @version $Id: $Id
 */
@Slf4j
public class TestGeneratorExecutor {
    private volatile boolean isCanceled = false;
    private final boolean loadFromCache;
    private final boolean useManualTests;
    private final boolean useAutoTests;

    /**
     * <p>Constructor for TestGeneratorExecutor.</p>
     */
    public TestGeneratorExecutor() {
        this(true, true, true);
    }

    /**
     * TestGenerator constructor
     * TODO: loadFromCache does not make sense if useAutoTests is false
     *
     * @param useAutoTests a boolean.
     * @param loadFromCache a boolean.
     * @param useManualTests a boolean.
     */
    public TestGeneratorExecutor(boolean useAutoTests, boolean loadFromCache, boolean useManualTests) {
        this.useAutoTests = useAutoTests;
        this.loadFromCache = loadFromCache;
        this.useManualTests = useManualTests;

        // no auto && no manual tests do not make sense
        checkArgument(useAutoTests || useManualTests);

        // no auto && cache does not make sense TODO fix this
        checkArgument(useAutoTests || !loadFromCache);
    }

    private final Collection<TestGeneratorExecutorMonitor> progressMonitors = new ArrayList<>();


    /**
     * <p>cancel.</p>
     */
    public void cancel() {
        isCanceled = true;
    }


    /**
     * <p>generateTestSuite.</p>
     *
     * @param testFolder a {@link java.lang.String} object.
     * @param dataset a {@link org.aksw.rdfunit.sources.Source} object.
     * @param autoGenerators a {@link java.util.Collection} object.
     * @return a {@link org.aksw.rdfunit.model.interfaces.TestSuite} object.
     */
    public TestSuite generateTestSuite(String testFolder, TestSource dataset, Collection<TestGenerator> autoGenerators) {

        Collection<SchemaSource> sources = dataset.getReferencesSchemata();


        /*notify start of testing */
        for (TestGeneratorExecutorMonitor monitor : progressMonitors) {
            monitor.generationStarted(dataset, sources.size());
        }

        Collection<TestCase> allTests = new ArrayList<>();
        for (SchemaSource s : sources) {
            if (isCanceled) {
                break;
            }

            if (s.getModel() == null || s.getModel().isEmpty()) {
                log.error("Trying to generate tests for {} but cannot read source", s.getUri());
                continue;
            }

            //Generate auto tests from schema
            if (useAutoTests) {
                allTests.addAll(generateAutoTestsForSchemaSource(testFolder, s, autoGenerators));
            }

            //Find manual tests for schema
            if (useManualTests) {
                allTests.addAll(generateManualTestsForSource(testFolder, s));
            }

            // Shacl Generator
            Collection<TestCase> shaclTests = new ShaclTestGenerator().generate(s);
            if (! shaclTests.isEmpty()) {
                log.info("{} generated {} SHACL test cases", s.getUri(), shaclTests.size());
                allTests.addAll(shaclTests);
            }


            // manual tests
            allTests.addAll(BatchTestCaseReader.create().getTestCasesFromModel(s.getModel()));
        }

        //Find manual tests for dataset (if not canceled
        if (!isCanceled && useManualTests) {
            allTests.addAll(generateManualTestsForSource(testFolder, dataset));
        }

        /*notify start of testing */
        progressMonitors.forEach(TestGeneratorExecutorMonitor::generationFinished);

        return new TestSuite(allTests);
    }

    private Collection<TestCase> generateAutoTestsForSchemaSource(String testFolder, SchemaSource s, Collection<TestGenerator> autoGenerators) {
        Collection<TestCase> tests = new ArrayList<>();

        for (TestGeneratorExecutorMonitor monitor : progressMonitors) {
            monitor.sourceGenerationStarted(s, TestGenerationType.AutoGenerated);
        }

        try {
            String cachedTestsLocation = CacheUtils.getSourceAutoTestFile(testFolder, s);
            if (!loadFromCache) {
                cachedTestsLocation = ""; // non existing path
            }
            Collection<TestCase> testsAutoCached = TestUtils.instantiateTestsFromModel(
                    new RdfStreamReader(cachedTestsLocation).read());
            tests.addAll(testsAutoCached);
            log.info("{} contains {} automatically created tests (loaded from cache)", s.getUri(), testsAutoCached.size());

        } catch (RdfReaderException e) {
            // cannot read from file  / generate
            Collection<TestCase> testsAuto = new TestGeneratorTCInstantiator(autoGenerators, s).generate();
            tests.addAll(testsAuto);
            TestUtils.writeTestsToFile(testsAuto, new RdfFileWriter(CacheUtils.getSourceAutoTestFile(testFolder, s)));
            log.info("{} contains {} automatically created tests from TAGs", s.getUri(), testsAuto.size());
            log.debug("No cached tests for {}", s.getUri());
        }

        for (TestGeneratorExecutorMonitor monitor : progressMonitors) {
            monitor.sourceGenerationExecuted(s, TestGenerationType.AutoGenerated, tests.size());
        }

        return tests;
    }

    private Collection<TestCase> generateManualTestsForSource(String testFolder, Source s) {
        Collection<TestCase> tests = new ArrayList<>();

        for (TestGeneratorExecutorMonitor monitor : progressMonitors) {
            monitor.sourceGenerationStarted(s, TestGenerationType.ManuallyGenerated);
        }
        try {

            Collection<TestCase> testsManuals = new ManualRdfunitTestGenerator(testFolder).generate(s);

            tests.addAll(testsManuals);
            log.info("{} contains {} manually created tests", s.getUri(), testsManuals.size());
        } catch (IllegalArgumentException e) {
            // Do nothing, Manual tests do not exist
            log.debug("No manual tests found for {}", s.getUri());

        }

        for (TestGeneratorExecutorMonitor monitor : progressMonitors) {
            monitor.sourceGenerationExecuted(s, TestGenerationType.ManuallyGenerated, tests.size());
        }

        return tests;


    }


    /**
     * <p>addTestExecutorMonitor.</p>
     *
     * @param monitor a {@link org.aksw.rdfunit.tests.generators.monitors.TestGeneratorExecutorMonitor} object.
     */
    public void addTestExecutorMonitor(TestGeneratorExecutorMonitor monitor) {

        if (!progressMonitors.contains(monitor)) {
            progressMonitors.add(monitor);
        }
    }

    /**
     * <p>removeTestExecutorMonitor.</p>
     *
     * @param monitor a {@link org.aksw.rdfunit.tests.generators.monitors.TestGeneratorExecutorMonitor} object.
     */
    public void removeTestExecutorMonitor(TestGeneratorExecutorMonitor monitor) {
        progressMonitors.remove(monitor);
    }
}
