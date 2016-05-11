package mil.nga.giat.geowave.format.landsat8;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import mil.nga.giat.geowave.core.cli.annotations.GeowaveOperation;

@GeowaveOperation(name = "download", parentOperation = Landsat8Section.class)
@Parameters(commandDescription = "Download Landsat 8 imagery to a local directory")
public class Landsat8DownloadCommand extends
		Landsat8AnalyzeCommand
{
	private static final int DOWNLOAD_RETRY = 5;
	private final static Logger LOGGER = LoggerFactory.getLogger(Landsat8DownloadCommand.class);
	private static final String DOWNLOAD_DIRECTORY = "images";

	@ParametersDelegate
	protected Landsat8DownloadCommandLineOptions downloadOptions;

	public Landsat8DownloadCommand() {}

	@Override
	protected void nextBand(
			final SimpleFeature band,
			final AnalysisInfo analysisInfo ) {
		super.nextBand(
				band,
				analysisInfo);
		final String downloadUrl = (String) band.getAttribute(BandFeatureIterator.BAND_DOWNLOAD_ATTRIBUTE_NAME);
		final File localPath = getDownloadFile(
				band,
				landsatOptions.getWorkspaceDir());
		if (localPath.exists()) {
			if (downloadOptions.isOverwriteIfExists()) {
				localPath.delete();
			}
			else {
				return;
			}
		}
		localPath.getParentFile().mkdirs();
		InputStream in = null;
		// first download the gzipped file
		int retry = 0;
		boolean success = false;
		while (!success && (retry < DOWNLOAD_RETRY)) {
			try {
				if (retry > 0) {
					// wait for a second
					Thread.sleep(1000L);
				}
				in = new URL(
						downloadUrl).openStream();
				success = true;
				final FileOutputStream outStream = new FileOutputStream(
						localPath);
				IOUtils.copyLarge(
						in,
						outStream);
				outStream.close();
			}
			catch (final IOException | InterruptedException e) {
				LOGGER.error(
						"Unable to read image from public S3 '" + downloadUrl + "'; retry round " + ++retry,
						e);
			}
			finally {
				if (in != null) {
					IOUtils.closeQuietly(in);
				}
			}
		}
	}

	protected static File getDownloadFile(
			final SimpleFeature band,
			final String workspaceDirectory ) {
		final int path = (int) band.getAttribute(SceneFeatureIterator.PATH_ATTRIBUTE_NAME);
		final int row = (int) band.getAttribute(SceneFeatureIterator.ROW_ATTRIBUTE_NAME);
		final String entity = (String) band.getAttribute(SceneFeatureIterator.ENTITY_ID_ATTRIBUTE_NAME);
		return new File(
				workspaceDirectory + File.separator + DOWNLOAD_DIRECTORY + File.separator + path + File.separator + row + File.separator + entity + File.separator + band.getID() + ".TIF");

	}
}
