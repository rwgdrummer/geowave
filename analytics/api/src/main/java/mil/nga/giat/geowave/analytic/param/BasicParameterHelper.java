package mil.nga.giat.geowave.analytic.param;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mil.nga.giat.geowave.analytic.PropertyManagement;
import mil.nga.giat.geowave.analytic.ScopedJobConfiguration;
import mil.nga.giat.geowave.core.index.ByteArrayUtils;
import mil.nga.giat.geowave.mapreduce.GeoWaveConfiguratorBase;

public class BasicParameterHelper implements
		ParameterHelper<Object>
{
	final static Logger LOGGER = LoggerFactory.getLogger(BasicParameterHelper.class);
	private final ParameterEnum<?> parent;
	private final Class<Object> baseClass;

	public BasicParameterHelper(
			final ParameterEnum<?> parent,
			final Class<Object> baseClass,
			final String name,
			final String description,
			final boolean hasArg ) {
		this.baseClass = baseClass;
		this.parent = parent;
	}

	@Override
	public Class<Object> getBaseClass() {
		return baseClass;
	}

	@Override
	public void setValue(
			final Configuration config,
			final Class<?> scope,
			final Object value ) {
		setParameter(
				config,
				scope,
				value,
				parent);
	}

	private static final void setParameter(
			final Configuration config,
			final Class<?> clazz,
			final Object val,
			final ParameterEnum configItem ) {
		if (val != null) {
			if (val instanceof Long) {
				config.setLong(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						((Long) val));
			}
			else if (val instanceof Double) {
				config.setDouble(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						((Double) val));
			}
			else if (val instanceof Boolean) {
				config.setBoolean(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						((Boolean) val));
			}
			else if (val instanceof Integer) {
				config.setInt(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						((Integer) val));
			}
			else if (val instanceof Class) {
				config.setClass(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						((Class) val),
						((Class) val));
			}
			else if (val instanceof byte[]) {
				config.set(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						ByteArrayUtils.byteArrayToString((byte[]) val));
			}
			else {
				config.set(
						GeoWaveConfiguratorBase.enumToConfKey(
								clazz,
								configItem.self()),
						val.toString());
			}

		}
	}

	@Override
	public Object getValue(
			final JobContext context,
			final Class<?> scope,
			final Object defaultValue ) {
		final ScopedJobConfiguration scopedConfig = new ScopedJobConfiguration(
				context.getConfiguration(),
				scope);
		if (baseClass.isAssignableFrom(Integer.class)) {
			return Integer.valueOf(scopedConfig.getInt(
					parent.self(),
					((Integer) defaultValue).intValue()));
		}
		else if (baseClass.isAssignableFrom(String.class)) {
			return scopedConfig.getString(
					parent.self(),
					defaultValue.toString());
		}
		else if (baseClass.isAssignableFrom(Double.class)) {
			return scopedConfig.getDouble(
					parent.self(),
					(Double) defaultValue);
		}
		else if (baseClass.isAssignableFrom(byte[].class)) {
			return scopedConfig.getBytes(parent.self());
		}
		else if ((defaultValue == null) || (defaultValue instanceof Class)) {
			try {
				return scopedConfig.getInstance(
						parent.self(),
						baseClass,
						(Class) defaultValue);
			}
			catch (InstantiationException | IllegalAccessException e) {
				LOGGER.error(
						"Unable to get instance from job context",
						e);
			}
		}
		return null;
	}

	@Override
	public Object getValue(
			String stringValue ) {
		if (stringValue == null) {
			return null;
		}
		if (baseClass.isAssignableFrom(Boolean.class)) {
			return Boolean.parseBoolean(stringValue);
		}
		if (baseClass.isAssignableFrom(Integer.class)) {
			return Integer.parseInt(stringValue);
		}
		else if (baseClass.isAssignableFrom(String.class)) {
			return stringValue;
		}
		else if (baseClass.isAssignableFrom(Double.class)) {
			return Double.parseDouble(stringValue);
		}
		else if (baseClass.isAssignableFrom(byte[].class)) {
			return ByteArrayUtils.byteArrayFromString(stringValue);
		}
		else if (baseClass.isAssignableFrom(Class.class)) {
			try {
				return Class.forName(stringValue);
			}
			catch (ClassNotFoundException e) {
				throw new RuntimeException(
						"Couldn't load class: " + stringValue);
			}
		}
		return null;
	}

	@Override
	public Object getValue(
			final PropertyManagement propertyManagement ) {
		try {
			return propertyManagement.getProperty(parent);
		}
		catch (final Exception e) {
			LOGGER.error(
					"Unable to deserialize property '" + parent.toString() + "'",
					e);
			return null;
		}
	}

	@Override
	public void setValue(
			final PropertyManagement propertyManagement,
			final Object value ) {
		propertyManagement.store(
				parent,
				value);
	}
}
