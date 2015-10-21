package mil.nga.giat.geowave.datastore.accumulo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.StringUtils;
import mil.nga.giat.geowave.core.store.DataStoreEntryInfo;
import mil.nga.giat.geowave.core.store.IndexWriter;
import mil.nga.giat.geowave.core.store.adapter.IndexDependentDataAdapter;
import mil.nga.giat.geowave.core.store.adapter.RowMergingDataAdapter;
import mil.nga.giat.geowave.core.store.adapter.WritableDataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataAdapterStatsWrapper;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.core.store.adapter.statistics.StatsCompositionTool;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloDataStatisticsStore;
import mil.nga.giat.geowave.datastore.accumulo.util.AccumuloUtils;
import mil.nga.giat.geowave.datastore.accumulo.util.DataAdapterAndIndexCache;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.log4j.Logger;

/**
 * This class can write many entries for a single index by retaining a single
 * open writer. The first entry that is written will open a writer and it is the
 * responsibility of the caller to close this writer when complete.
 * 
 */
public class AccumuloIndexWriter implements
		IndexWriter
{
	private final static Logger LOGGER = Logger.getLogger(AccumuloIndexWriter.class);
	protected final PrimaryIndex index;
	protected final AccumuloOperations accumuloOperations;
	protected final AccumuloOptions accumuloOptions;
	protected final AccumuloDataStore dataStore;
	protected Writer writer;
	protected Writer altIdxWriter;

	protected boolean useAltIndex;
	protected String indexName;
	protected String altIdxTableName;

	protected boolean persistStats;
	protected int statsFlushCount = 0;
	protected boolean skipFlush = false;
	// just need a reasonable threshold.
	public static final int FLUSH_STATS_THRESHOLD = 16384;
	protected final Map<ByteArrayId, StatsCompositionTool<?>> statsMap = new HashMap<ByteArrayId, StatsCompositionTool<?>>();

	public AccumuloIndexWriter(
			final PrimaryIndex index,
			final AccumuloOperations accumuloOperations,
			final AccumuloDataStore dataStore ) {
		this(
				index,
				accumuloOperations,
				new AccumuloOptions(),
				dataStore);
	}

	public AccumuloIndexWriter(
			final PrimaryIndex index,
			final AccumuloOperations accumuloOperations,
			final AccumuloOptions accumuloOptions,
			final AccumuloDataStore dataStore ) {
		this.index = index;
		this.accumuloOperations = accumuloOperations;
		this.accumuloOptions = accumuloOptions;
		this.dataStore = dataStore;
		initialize();
	}

	private void initialize() {
		indexName = StringUtils.stringFromBinary(index.getId().getBytes());
		altIdxTableName = indexName + AccumuloUtils.ALT_INDEX_TABLE;

		useAltIndex = accumuloOptions.isUseAltIndex();
		persistStats = accumuloOptions.isPersistDataStatistics();
		if (useAltIndex) {
			if (accumuloOperations.tableExists(indexName)) {
				if (!accumuloOperations.tableExists(altIdxTableName)) {
					useAltIndex = false;
					LOGGER.info("Requested alternate index table [" + altIdxTableName + "] does not exist.");
				}
			}
			else {
				if (accumuloOperations.tableExists(altIdxTableName)) {
					accumuloOperations.deleteTable(altIdxTableName);
					LOGGER.warn("Deleting current alternate index table [" + altIdxTableName + "] as main table does not yet exist.");
				}
			}
		}

		try {
			final Object v = System.getProperty("AccumuloIndexWriter.skipFlush");
			skipFlush = ((v != null) && v.toString().equalsIgnoreCase(
					"true"));
		}
		catch (final Exception ex) {
			LOGGER.error(
					"Unable to determine property AccumuloIndexWriter.skipFlush",
					ex);
		}
	}

	private synchronized void ensureOpen() {
		if (writer == null) {
			try {
				writer = accumuloOperations.createWriter(
						StringUtils.stringFromBinary(index.getId().getBytes()),
						accumuloOptions.isCreateTable());
			}
			catch (final TableNotFoundException e) {
				LOGGER.error(
						"Unable to open writer",
						e);
			}
		}
		if (useAltIndex && (altIdxWriter == null)) {
			try {
				altIdxWriter = accumuloOperations.createWriter(
						altIdxTableName,
						accumuloOptions.isCreateTable());
			}
			catch (final TableNotFoundException e) {
				LOGGER.error(
						"Unable to open writer",
						e);
			}
		}
	}

	private synchronized void closeInternal() {
		if (writer != null) {
			writer.close();
			writer = null;
		}
		if (useAltIndex && (altIdxWriter != null)) {
			altIdxWriter.close();
			altIdxWriter = null;
		}
	}

	@Override
	public PrimaryIndex getIndex() {
		return index;
	}

	@Override
	public <T> List<ByteArrayId> write(
			final WritableDataAdapter<T> writableAdapter,
			final T entry ) {
		if (writableAdapter instanceof IndexDependentDataAdapter) {
			final IndexDependentDataAdapter adapter = ((IndexDependentDataAdapter) writableAdapter);
			final Iterator<T> indexedEntries = adapter.convertToIndex(
					index,
					entry);
			final List<ByteArrayId> rowIds = new ArrayList<ByteArrayId>();
			while (indexedEntries.hasNext()) {
				rowIds.addAll(writeInternal(
						adapter,
						indexedEntries.next()));
			}
			return rowIds;
		}
		else {
			return writeInternal(
					writableAdapter,
					entry);
		}
	}

	public <T> List<ByteArrayId> writeInternal(
			final WritableDataAdapter<T> writableAdapter,
			final T entry ) {
		final ByteArrayId adapterIdObj = writableAdapter.getAdapterId();

		final byte[] adapterId = writableAdapter.getAdapterId().getBytes();

		try {
			if (writableAdapter instanceof RowMergingDataAdapter) {
				if (!DataAdapterAndIndexCache.getInstance(
						RowMergingAdapterOptionProvider.ROW_MERGING_ADAPTER_CACHE_ID).add(
						adapterIdObj,
						indexName)) {
					AccumuloUtils.attachRowMergingIterators(
							((RowMergingDataAdapter<?, ?>) writableAdapter),
							accumuloOperations,
							indexName,
							accumuloOptions.isCreateTable());
				}
			}
			if (accumuloOptions.isUseLocalityGroups() && !accumuloOperations.localityGroupExists(
					indexName,
					adapterId)) {
				accumuloOperations.addLocalityGroup(
						indexName,
						adapterId);
			}
		}
		catch (AccumuloException | TableNotFoundException | AccumuloSecurityException e) {
			LOGGER.error(
					"Unable to determine existence of locality group [" + writableAdapter.getAdapterId().getString() + "]",
					e);
		}
		DataStoreEntryInfo entryInfo;
		synchronized (this) {
			dataStore.store(writableAdapter);
			dataStore.store(index);

			ensureOpen();
			entryInfo = AccumuloUtils.write(
					writableAdapter,
					index,
					entry,
					writer);

			if (useAltIndex) {
				AccumuloUtils.writeAltIndex(
						writableAdapter,
						entryInfo,
						entry,
						altIdxWriter);
			}
			if (persistStats) {
				recordStats(
						adapterIdObj,
						entryInfo,
						writableAdapter,
						entry);
			}
		}
		return entryInfo.getRowIds();
	}

	@Override
	public void close() {
		// thread safe close
		closeInternal();

		// write the statistics and clear it
		flushStats();
	}

	@Override
	public <T> void setupAdapter(
			final WritableDataAdapter<T> writableAdapter ) {
		try {
			final ByteArrayId adapterIdObj = writableAdapter.getAdapterId();

			final byte[] adapterId = writableAdapter.getAdapterId().getBytes();
			if (writableAdapter instanceof RowMergingDataAdapter) {
				if (!DataAdapterAndIndexCache.getInstance(
						RowMergingAdapterOptionProvider.ROW_MERGING_ADAPTER_CACHE_ID).add(
						adapterIdObj,
						indexName)) {
					AccumuloUtils.attachRowMergingIterators(
							((RowMergingDataAdapter<?, ?>) writableAdapter),
							accumuloOperations,
							indexName,
							accumuloOptions.isCreateTable());
				}
			}
			if (accumuloOptions.isUseLocalityGroups() && !accumuloOperations.localityGroupExists(
					indexName,
					adapterId)) {
				accumuloOperations.addLocalityGroup(
						indexName,
						adapterId);
			}
		}
		catch (AccumuloException | TableNotFoundException | AccumuloSecurityException e) {
			LOGGER.error(
					"Unable to determine existence of locality group [" + writableAdapter.getAdapterId().getString() + "]",
					e);
		}
	}

	@Override
	public synchronized void flush() {
		// thread safe flush of the writers
		if (writer != null) {
			writer.flush();
		}
		if (useAltIndex && (altIdxWriter != null)) {
			altIdxWriter.flush();
		}
		flushStats();

	}

	private synchronized void flushStats() {
		// write the statistics and clear it
		if (persistStats) {
			final DataStatisticsStore statsStore = new AccumuloDataStatisticsStore(
					accumuloOperations);
			for (final StatsCompositionTool<?> tool : statsMap.values()) {
				tool.setStatisticsStore(statsStore);
				tool.flush();
			}
			statsMap.clear();
		}
	}

	private synchronized <T> void recordStats(
			final ByteArrayId adapterIdObj,
			final DataStoreEntryInfo entryInfo,
			final WritableDataAdapter<T> writableAdapter,
			final T entry ) {

		StatsCompositionTool<T> tool = (StatsCompositionTool<T>) statsMap.get(adapterIdObj);
		if (tool == null) {
			tool = new StatsCompositionTool<T>(
					new DataAdapterStatsWrapper<T>(
							index,
							writableAdapter));
			statsMap.put(
					adapterIdObj,
					tool);
		}
		tool.entryIngested(
				entryInfo,
				entry);
		statsFlushCount++;
		if (!skipFlush && (statsFlushCount > FLUSH_STATS_THRESHOLD)) {
			statsFlushCount = 0;
			flushStats();
		}
	}
}