package org.broadinstitute.hellbender.tools.copynumber.legacy;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hdf5.HDF5File;
import org.broadinstitute.hdf5.HDF5Library;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.ExomeStandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.legacy.coverage.denoising.rsvd.HDF5RandomizedSVDReadCountPanelOfNormals;
import org.broadinstitute.hellbender.tools.copynumber.legacy.coverage.denoising.rsvd.SVDDenoisedCopyRatioResult;
import org.broadinstitute.hellbender.tools.copynumber.legacy.coverage.denoising.rsvd.SVDDenoisingUtils;
import org.broadinstitute.hellbender.tools.copynumber.legacy.coverage.denoising.rsvd.SVDReadCountPanelOfNormals;
import org.broadinstitute.hellbender.tools.exome.ReadCountCollection;
import org.broadinstitute.hellbender.tools.exome.ReadCountCollectionUtils;
import org.broadinstitute.hellbender.utils.io.IOUtils;

import java.io.File;
import java.io.IOException;

/**
 * Denoises read counts given the panel of normals (PoN) created by {@link CreateReadCountPanelOfNormals} to produce
 * a copy-ratio profile.
 *
 * <h3>Examples</h3>
 * <p>
 *     The following command is for either whole exome sequencing (WES) or whole genome sequencing (WGS) data.
 * </p>
 *
 * <pre>
 * gatk-launch --javaOptions "-Xmx4g" DenoiseReadCounts \
 *   --input tumor.coverage.tsv \
 *   --panelOfNormals panel_of_normals.pon \
 *   --tangentNormalized tumor.tn.tsv \
 *   --preTangentNormalized tumor.preTN.tsv
 * </pre>
 *
 * The resulting copy-ratio profile is log2 transformed.
 *
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Denoise read counts using a panel of normals",
        oneLineSummary = "Denoise read counts using a panel of normals",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
public final class DenoiseReadCounts extends CommandLineProgram {
    static final String NUMBER_OF_EIGENSAMPLES_LONG_NAME = "numberOfEigensamples";
    static final String NUMBER_OF_EIGENSAMPLES_SHORT_NAME = "numEigen";

    @Argument(
            doc = "Input read-count file containing integer read counts in genomic intervals for a single case sample.",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME
    )
    private File inputReadCountFile;

    @Argument(
            doc = "Input HDF5 file containing the panel of normals (output of CreateReadCountPanelOfNormals).",
            fullName = ExomeStandardArgumentDefinitions.PON_FILE_LONG_NAME,
            shortName = ExomeStandardArgumentDefinitions.PON_FILE_SHORT_NAME
    )
    private File inputPanelOfNormalsFile;

    @Argument(
            doc = "Output file for standardized (pre-tangent-normalized) copy-ratio profile.",
            fullName = ExomeStandardArgumentDefinitions.PRE_TANGENT_NORMALIZED_COUNTS_FILE_LONG_NAME,
            shortName = ExomeStandardArgumentDefinitions.PRE_TANGENT_NORMALIZED_COUNTS_FILE_SHORT_NAME
    )
    private File standardizedProfileFile;

    @Argument(
            doc = "Output file for denoised (tangent-normalized) copy-ratio profile.",
            fullName = ExomeStandardArgumentDefinitions.TANGENT_NORMALIZED_COUNTS_FILE_LONG_NAME,
            shortName = ExomeStandardArgumentDefinitions.TANGENT_NORMALIZED_COUNTS_FILE_SHORT_NAME
    )
    private File denoisedProfileFile;

    @Argument(
            doc = "Number of eigensamples to use for denoising.  " +
                    "If not specified or if the number of eigensamples available in the panel of normals " +
                    "is smaller than this, all eigensamples will be used.",
            fullName = NUMBER_OF_EIGENSAMPLES_LONG_NAME,
            shortName = NUMBER_OF_EIGENSAMPLES_SHORT_NAME,
            minValue = 1,
            optional = true
    )
    private Integer numEigensamplesRequested = null;

    @Override
    protected Object doWork() {
        if (!new HDF5Library().load(null)) { //Note: passing null means using the default temp dir.
            throw new UserException.HardwareFeatureException("Cannot load the required HDF5 library. " +
                    "HDF5 is currently supported on x86-64 architecture and Linux or OSX systems.");
        }

        validateInputFiles();

        try (final HDF5File hdf5PanelOfNormalsFile = new HDF5File(inputPanelOfNormalsFile)) {  //HDF5File implements AutoCloseable
            logger.info(String.format("Reading read-count file (%s)...", inputReadCountFile));
            final ReadCountCollection readCounts = ReadCountCollectionUtils.parse(inputReadCountFile);
            final SVDReadCountPanelOfNormals panelOfNormals = HDF5RandomizedSVDReadCountPanelOfNormals.read(hdf5PanelOfNormalsFile);

            //check that read-count collection contains single sample and integer counts
            SVDDenoisingUtils.validateReadCounts(readCounts);

            //perform denoising and write result
            final int numEigensamples =
                    numEigensamplesRequested == null ?
                            panelOfNormals.getNumEigensamples() :
                            Math.min(panelOfNormals.getNumEigensamples(), this.numEigensamplesRequested);
            if (numEigensamplesRequested != null && numEigensamples < numEigensamplesRequested) {
                logger.warn(String.format("%d eigensamples were requested but only %d are available in the panel of normals...",
                        numEigensamplesRequested, numEigensamples));
            }
            final SVDDenoisedCopyRatioResult denoisedCopyRatioResult = panelOfNormals.denoise(readCounts, numEigensamples);

            logger.info("Writing standardized and denoised copy-ratio profiles...");
            denoisedCopyRatioResult.write(standardizedProfileFile, denoisedProfileFile);

            logger.info("Read counts successfully denoised.");

            return "SUCCESS";
        } catch (final IOException e) {
            throw new UserException.CouldNotReadInputFile(inputReadCountFile);
        }
    }

    private void validateInputFiles() {
        IOUtils.canReadFile(inputPanelOfNormalsFile);
        IOUtils.canReadFile(inputReadCountFile);
    }
}