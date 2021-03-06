package org.broadinstitute.hellbender.tools.exome.segmentation;

import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.cmdline.ExomeStandardArgumentDefinitions;
import org.broadinstitute.hellbender.tools.exome.ModeledSegment;
import org.broadinstitute.hellbender.tools.exome.SegmentUtils;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Created by davidben on 7/25/16.
 */
public class PerformCopyRatioSegmentationIntegrationTest extends CommandLineProgramTest {
    private static final String TOOLS_TEST_DIRECTORY = publicTestDir + "org/broadinstitute/hellbender/tools/exome/";
    private static final File LOG2_TN_COVERAGE_FILE = new File(TOOLS_TEST_DIRECTORY, "coverages-for-copy-ratio-modeller.tsv" );

    // checks that segmentation output is created -- only the unit test checks correctness of results
    @Test
    public void testCommandLine() throws IOException {
        final File tnCoverageFile = LOG2_TN_COVERAGE_FILE;
        final File outputSegmentFile = createTempFile("segments", ".seg");
        final int initialNumStates = 10;
        final String[] arguments = {
                "-" + ExomeStandardArgumentDefinitions.TANGENT_NORMALIZED_COUNTS_FILE_SHORT_NAME, tnCoverageFile.getAbsolutePath(),
                "-" + PerformCopyRatioSegmentation.INITIAL_NUM_STATES_SHORT_NAME, Integer.toString(initialNumStates),
                "-" + ExomeStandardArgumentDefinitions.SEGMENT_FILE_SHORT_NAME, outputSegmentFile.getAbsolutePath()
        };
        runCommandLine(arguments);

        final List<ModeledSegment> segments = SegmentUtils.readModeledSegmentsFromSegmentFile(outputSegmentFile);
    }
}