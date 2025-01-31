package mil.nga.giat.geowave.analytic.mapreduce.operations;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import mil.nga.giat.geowave.analytic.PropertyManagement;
import mil.nga.giat.geowave.analytic.mapreduce.clustering.runner.MultiLevelKMeansClusteringJobRunner;
import mil.nga.giat.geowave.analytic.mapreduce.operations.options.CommonOptions;
import mil.nga.giat.geowave.analytic.mapreduce.operations.options.KMeansCommonOptions;
import mil.nga.giat.geowave.analytic.mapreduce.operations.options.PropertyManagementConverter;
import mil.nga.giat.geowave.analytic.param.StoreParameters;
import mil.nga.giat.geowave.analytic.param.ExtractParameters.Extract;
import mil.nga.giat.geowave.analytic.store.PersistableStore;
import mil.nga.giat.geowave.core.cli.annotations.GeowaveOperation;
import mil.nga.giat.geowave.core.cli.api.Command;
import mil.nga.giat.geowave.core.cli.api.DefaultOperation;
import mil.nga.giat.geowave.core.cli.api.OperationParams;
import mil.nga.giat.geowave.core.cli.operations.config.options.ConfigOptions;
import mil.nga.giat.geowave.core.store.operations.remote.options.DataStorePluginOptions;
import mil.nga.giat.geowave.core.store.operations.remote.options.StoreLoader;

@GeowaveOperation(name = "kmeansparallel", parentOperation = AnalyticSection.class)
@Parameters(commandDescription = "KMeans Parallel Clustering")
public class KmeansParallelCommand extends
		DefaultOperation implements
		Command
{

	@Parameter(description = "<storename>")
	private List<String> parameters = new ArrayList<String>();

	@ParametersDelegate
	private CommonOptions commonOptions = new CommonOptions();

	@ParametersDelegate
	private KMeansCommonOptions kmeansCommonOptions = new KMeansCommonOptions();

	private DataStorePluginOptions inputStoreOptions = null;

	@Override
	public void execute(
			OperationParams params )
			throws Exception {

		// Ensure we have all the required arguments
		if (parameters.size() != 1) {
			throw new ParameterException(
					"Requires arguments: <storename>");
		}

		String inputStoreName = parameters.get(0);

		// Config file
		File configFile = (File) params.getContext().get(
				ConfigOptions.PROPERTIES_FILE_CONTEXT);

		// Attempt to load store.
		if (inputStoreOptions == null) {
			StoreLoader inputStoreLoader = new StoreLoader(
					inputStoreName);
			if (!inputStoreLoader.loadFromConfig(configFile)) {
				throw new ParameterException(
						"Cannot find store name: " + inputStoreLoader.getStoreName());
			}
			inputStoreOptions = inputStoreLoader.getDataStorePlugin();
		}

		// Save a reference to the store in the property management.
		PersistableStore persistedStore = new PersistableStore(
				inputStoreOptions);
		final PropertyManagement properties = new PropertyManagement();
		properties.store(
				StoreParameters.StoreParam.INPUT_STORE,
				persistedStore);

		// Convert properties from DBScanOptions and CommonOptions
		PropertyManagementConverter converter = new PropertyManagementConverter(
				properties);
		converter.readProperties(commonOptions);
		converter.readProperties(kmeansCommonOptions);
		properties.store(
				Extract.QUERY_OPTIONS,
				commonOptions.buildQueryOptions());

		MultiLevelKMeansClusteringJobRunner runner = new MultiLevelKMeansClusteringJobRunner();
		int status = runner.run(properties);
		if (status != 0) {
			throw new RuntimeException(
					"Failed to execute: " + status);
		}
	}

	public List<String> getParameters() {
		return parameters;
	}

	public void setParameters(
			String storeName ) {
		this.parameters = new ArrayList<String>();
		this.parameters.add(storeName);
	}

	public CommonOptions getCommonOptions() {
		return commonOptions;
	}

	public void setCommonOptions(
			CommonOptions commonOptions ) {
		this.commonOptions = commonOptions;
	}

	public KMeansCommonOptions getKmeansCommonOptions() {
		return kmeansCommonOptions;
	}

	public void setKmeansCommonOptions(
			KMeansCommonOptions kmeansCommonOptions ) {
		this.kmeansCommonOptions = kmeansCommonOptions;
	}

	public DataStorePluginOptions getInputStoreOptions() {
		return inputStoreOptions;
	}

	public void setInputStoreOptions(
			DataStorePluginOptions inputStoreOptions ) {
		this.inputStoreOptions = inputStoreOptions;
	}
}
