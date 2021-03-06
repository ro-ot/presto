/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.Database;
import com.facebook.presto.hive.metastore.HivePrivilegeInfo;
import com.facebook.presto.hive.metastore.HivePrivilegeInfo.HivePrivilege;
import com.facebook.presto.hive.metastore.Partition;
import com.facebook.presto.hive.metastore.PrincipalPrivileges;
import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore;
import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore.WriteMode;
import com.facebook.presto.hive.metastore.StorageFormat;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.hive.statistics.HiveStatisticsProvider;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.facebook.presto.spi.ConnectorNewTableLayout;
import com.facebook.presto.spi.ConnectorNodePartitioning;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.ConnectorViewDefinition;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.DiscretePredicates;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.ViewNotFoundException;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.connector.ConnectorOutputMetadata;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.security.GrantInfo;
import com.facebook.presto.spi.security.Privilege;
import com.facebook.presto.spi.security.PrivilegeInfo;
import com.facebook.presto.spi.statistics.TableStatistics;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.airlift.json.JsonCodec;
import io.airlift.slice.Slice;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator;
import org.apache.hadoop.mapred.JobConf;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.HiveBucketing.getHiveBucketHandle;
import static com.facebook.presto.hive.HiveColumnHandle.BUCKET_COLUMN_NAME;
import static com.facebook.presto.hive.HiveColumnHandle.ColumnType.HIDDEN;
import static com.facebook.presto.hive.HiveColumnHandle.ColumnType.PARTITION_KEY;
import static com.facebook.presto.hive.HiveColumnHandle.ColumnType.REGULAR;
import static com.facebook.presto.hive.HiveColumnHandle.PATH_COLUMN_NAME;
import static com.facebook.presto.hive.HiveColumnHandle.updateRowIdHandle;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_COLUMN_ORDER_MISMATCH;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CONCURRENT_MODIFICATION_DETECTED;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_TIMEZONE_MISMATCH;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_UNKNOWN_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_UNSUPPORTED_FORMAT;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_WRITER_CLOSE_ERROR;
import static com.facebook.presto.hive.HivePartitionManager.extractPartitionKeyValues;
import static com.facebook.presto.hive.HiveSessionProperties.isBucketExecutionEnabled;
import static com.facebook.presto.hive.HiveSessionProperties.isStatisticsEnabled;
import static com.facebook.presto.hive.HiveTableProperties.BUCKETED_BY_PROPERTY;
import static com.facebook.presto.hive.HiveTableProperties.BUCKET_COUNT_PROPERTY;
import static com.facebook.presto.hive.HiveTableProperties.EXTERNAL_LOCATION_PROPERTY;
import static com.facebook.presto.hive.HiveTableProperties.PARTITIONED_BY_PROPERTY;
import static com.facebook.presto.hive.HiveTableProperties.STORAGE_FORMAT_PROPERTY;
import static com.facebook.presto.hive.HiveTableProperties.getBucketProperty;
import static com.facebook.presto.hive.HiveTableProperties.getExternalLocation;
import static com.facebook.presto.hive.HiveTableProperties.getHiveStorageFormat;
import static com.facebook.presto.hive.HiveTableProperties.getPartitionedBy;
import static com.facebook.presto.hive.HiveType.HIVE_STRING;
import static com.facebook.presto.hive.HiveType.toHiveType;
import static com.facebook.presto.hive.HiveUtil.PRESTO_VIEW_FLAG;
import static com.facebook.presto.hive.HiveUtil.columnExtraInfo;
import static com.facebook.presto.hive.HiveUtil.decodeViewData;
import static com.facebook.presto.hive.HiveUtil.encodeViewData;
import static com.facebook.presto.hive.HiveUtil.hiveColumnHandles;
import static com.facebook.presto.hive.HiveUtil.schemaTableName;
import static com.facebook.presto.hive.HiveUtil.toPartitionValues;
import static com.facebook.presto.hive.HiveWriteUtils.checkTableIsWritable;
import static com.facebook.presto.hive.HiveWriteUtils.initializeSerializer;
import static com.facebook.presto.hive.HiveWriteUtils.isWritableType;
import static com.facebook.presto.hive.metastore.HivePrivilegeInfo.toHivePrivilege;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getHiveSchema;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getProtectMode;
import static com.facebook.presto.hive.metastore.MetastoreUtil.verifyOnline;
import static com.facebook.presto.hive.metastore.PrincipalType.USER;
import static com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore.WriteMode.DIRECT_TO_TARGET_EXISTING_DIRECTORY;
import static com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore.WriteMode.DIRECT_TO_TARGET_NEW_DIRECTORY;
import static com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore.WriteMode.STAGE_AND_MOVE_TO_TARGET_DIRECTORY;
import static com.facebook.presto.hive.metastore.StorageFormat.VIEW_STORAGE_FORMAT;
import static com.facebook.presto.hive.metastore.StorageFormat.fromHiveStorageFormat;
import static com.facebook.presto.hive.util.ConfigurationUtils.toJobConf;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_SCHEMA_PROPERTY;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_TABLE_PROPERTY;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.StandardErrorCode.SCHEMA_NOT_EMPTY;
import static com.facebook.presto.spi.predicate.TupleDomain.withColumnDomains;
import static com.facebook.presto.spi.statistics.TableStatistics.EMPTY_STATISTICS;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.concat;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.hadoop.hive.metastore.TableType.EXTERNAL_TABLE;
import static org.apache.hadoop.hive.metastore.TableType.MANAGED_TABLE;

public class HiveMetadata
        implements ConnectorMetadata
{
    public static final String PRESTO_VERSION_NAME = "presto_version";
    public static final String PRESTO_QUERY_ID_NAME = "presto_query_id";
    public static final String TABLE_COMMENT = "comment";

    private final String connectorId;
    private final boolean allowCorruptWritesForTesting;
    private final SemiTransactionalHiveMetastore metastore;
    private final HdfsEnvironment hdfsEnvironment;
    private final HivePartitionManager partitionManager;
    private final DateTimeZone timeZone;
    private final TypeManager typeManager;
    private final LocationService locationService;
    private final TableParameterCodec tableParameterCodec;
    private final JsonCodec<PartitionUpdate> partitionUpdateCodec;
    private final boolean respectTableFormat;
    private final boolean bucketWritingEnabled;
    private final boolean writesToNonManagedTablesEnabled;
    private final HiveStorageFormat defaultStorageFormat;
    private final TypeTranslator typeTranslator;
    private final String prestoVersion;
    private final HiveStatisticsProvider hiveStatisticsProvider;

    public HiveMetadata(
            String connectorId,
            SemiTransactionalHiveMetastore metastore,
            HdfsEnvironment hdfsEnvironment,
            HivePartitionManager partitionManager,
            DateTimeZone timeZone,
            boolean allowCorruptWritesForTesting,
            boolean respectTableFormat,
            boolean bucketWritingEnabled,
            boolean writesToNonManagedTablesEnabled,
            HiveStorageFormat defaultStorageFormat,
            TypeManager typeManager,
            LocationService locationService,
            TableParameterCodec tableParameterCodec,
            JsonCodec<PartitionUpdate> partitionUpdateCodec,
            TypeTranslator typeTranslator,
            String prestoVersion,
            HiveStatisticsProvider hiveStatisticsProvider)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null");

        this.allowCorruptWritesForTesting = allowCorruptWritesForTesting;

        this.metastore = requireNonNull(metastore, "metastore is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.partitionManager = requireNonNull(partitionManager, "partitionManager is null");
        this.timeZone = requireNonNull(timeZone, "timeZone is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.locationService = requireNonNull(locationService, "locationService is null");
        this.tableParameterCodec = requireNonNull(tableParameterCodec, "tableParameterCodec is null");
        this.partitionUpdateCodec = requireNonNull(partitionUpdateCodec, "partitionUpdateCodec is null");
        this.respectTableFormat = respectTableFormat;
        this.bucketWritingEnabled = bucketWritingEnabled;
        this.writesToNonManagedTablesEnabled = writesToNonManagedTablesEnabled;
        this.defaultStorageFormat = requireNonNull(defaultStorageFormat, "defaultStorageFormat is null");
        this.typeTranslator = requireNonNull(typeTranslator, "typeTranslator is null");
        this.prestoVersion = requireNonNull(prestoVersion, "prestoVersion is null");
        this.hiveStatisticsProvider = requireNonNull(hiveStatisticsProvider, "hiveStatisticsProvider is null");
    }

    public SemiTransactionalHiveMetastore getMetastore()
    {
        return metastore;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return metastore.getAllDatabases();
    }

    @Override
    public HiveTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(tableName, "tableName is null");
        Optional<Table> table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (!table.isPresent()) {
            return null;
        }
        verifyOnline(tableName, Optional.empty(), getProtectMode(table.get()), table.get().getParameters());
        return new HiveTableHandle(connectorId, tableName.getSchemaName(), tableName.getTableName());
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        requireNonNull(tableHandle, "tableHandle is null");
        SchemaTableName tableName = schemaTableName(tableHandle);
        return getTableMetadata(tableName);
    }

    private ConnectorTableMetadata getTableMetadata(SchemaTableName tableName)
    {
        Optional<Table> table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (!table.isPresent() || table.get().getTableType().equals(TableType.VIRTUAL_VIEW.name())) {
            throw new TableNotFoundException(tableName);
        }

        Function<HiveColumnHandle, ColumnMetadata> metadataGetter = columnMetadataGetter(table.get(), typeManager);
        ImmutableList.Builder<ColumnMetadata> columns = ImmutableList.builder();
        for (HiveColumnHandle columnHandle : hiveColumnHandles(connectorId, table.get())) {
            columns.add(metadataGetter.apply(columnHandle));
        }

        ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        if (table.get().getTableType().equals(EXTERNAL_TABLE.name())) {
            properties.put(EXTERNAL_LOCATION_PROPERTY, table.get().getStorage().getLocation());
        }
        try {
            HiveStorageFormat format = extractHiveStorageFormat(table.get());
            properties.put(STORAGE_FORMAT_PROPERTY, format);
        }
        catch (PrestoException ignored) {
            // todo fail if format is not known
        }
        List<String> partitionedBy = table.get().getPartitionColumns().stream()
                .map(Column::getName)
                .collect(toList());
        if (!partitionedBy.isEmpty()) {
            properties.put(PARTITIONED_BY_PROPERTY, partitionedBy);
        }
        Optional<HiveBucketProperty> bucketProperty = table.get().getStorage().getBucketProperty();
        if (bucketProperty.isPresent()) {
            properties.put(BUCKET_COUNT_PROPERTY, bucketProperty.get().getBucketCount());
            properties.put(BUCKETED_BY_PROPERTY, bucketProperty.get().getBucketedBy());
        }
        properties.putAll(tableParameterCodec.decode(table.get().getParameters()));

        Optional<String> comment = Optional.ofNullable(table.get().getParameters().get(TABLE_COMMENT));

        return new ConnectorTableMetadata(tableName, columns.build(), properties.build(), comment);
    }

    @Override
    public Optional<Object> getInfo(ConnectorTableLayoutHandle layoutHandle)
    {
        HiveTableLayoutHandle tableLayoutHandle = (HiveTableLayoutHandle) layoutHandle;
        if (tableLayoutHandle.getPartitions().isPresent()) {
            return Optional.of(new HiveInputInfo(tableLayoutHandle.getPartitions().get().stream()
                    .map(HivePartition::getPartitionId)
                    .collect(Collectors.toList())));
        }

        return Optional.empty();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
        for (String schemaName : listSchemas(session, schemaNameOrNull)) {
            for (String tableName : metastore.getAllTables(schemaName).orElse(emptyList())) {
                tableNames.add(new SchemaTableName(schemaName, tableName));
            }
        }
        return tableNames.build();
    }

    private List<String> listSchemas(ConnectorSession session, String schemaNameOrNull)
    {
        if (schemaNameOrNull == null) {
            return listSchemaNames(session);
        }
        return ImmutableList.of(schemaNameOrNull);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        SchemaTableName tableName = schemaTableName(tableHandle);
        Optional<Table> table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (!table.isPresent()) {
            throw new TableNotFoundException(tableName);
        }
        ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();
        for (HiveColumnHandle columnHandle : hiveColumnHandles(connectorId, table.get())) {
            columnHandles.put(columnHandle.getName(), columnHandle);
        }
        return columnHandles.build();
    }

    @SuppressWarnings("TryWithIdenticalCatches")
    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        for (SchemaTableName tableName : listTables(session, prefix)) {
            try {
                columns.put(tableName, getTableMetadata(tableName).getColumns());
            }
            catch (HiveViewNotSupportedException e) {
                // view is not supported
            }
            catch (TableNotFoundException e) {
                // table disappeared during listing operation
            }
        }
        return columns.build();
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint)
    {
        if (!isStatisticsEnabled(session)) {
            return EMPTY_STATISTICS;
        }
        List<HivePartition> hivePartitions = partitionManager.getPartitions(metastore, tableHandle, constraint).getPartitions();
        Map<String, ColumnHandle> tableColumns = getColumnHandles(session, tableHandle);
        return hiveStatisticsProvider.getTableStatistics(session, tableHandle, hivePartitions, tableColumns);
    }

    private List<SchemaTableName> listTables(ConnectorSession session, SchemaTablePrefix prefix)
    {
        if (prefix.getSchemaName() == null || prefix.getTableName() == null) {
            return listTables(session, prefix.getSchemaName());
        }
        return ImmutableList.of(new SchemaTableName(prefix.getSchemaName(), prefix.getTableName()));
    }

    /**
     * NOTE: This method does not return column comment
     */
    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((HiveColumnHandle) columnHandle).getColumnMetadata(typeManager);
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties)
    {
        Optional<String> location = HiveSchemaProperties.getLocation(properties).map(locationUri -> {
            try {
                hdfsEnvironment.getFileSystem(session.getUser(), new Path(locationUri));
            }
            catch (IOException e) {
                throw new PrestoException(INVALID_SCHEMA_PROPERTY, "Invalid location URI: " + locationUri, e);
            }
            return locationUri;
        });

        Database database = Database.builder()
                .setDatabaseName(schemaName)
                .setLocation(location)
                .setOwnerType(USER)
                .setOwnerName(session.getUser())
                .build();

        metastore.createDatabase(database);
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        // basic sanity check to provide a better error message
        if (!listTables(session, schemaName).isEmpty() ||
                !listViews(session, schemaName).isEmpty()) {
            throw new PrestoException(SCHEMA_NOT_EMPTY, "Schema not empty: " + schemaName);
        }
        metastore.dropDatabase(schemaName);
    }

    @Override
    public void renameSchema(ConnectorSession session, String source, String target)
    {
        metastore.renameDatabase(source, target);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();
        List<String> partitionedBy = getPartitionedBy(tableMetadata.getProperties());
        Optional<HiveBucketProperty> bucketProperty = getBucketProperty(tableMetadata.getProperties());
        if (bucketProperty.isPresent() && !bucketWritingEnabled) {
            throw new PrestoException(NOT_SUPPORTED, "Writing to bucketed Hive table has been temporarily disabled");
        }
        List<HiveColumnHandle> columnHandles = getColumnHandles(connectorId, tableMetadata, ImmutableSet.copyOf(partitionedBy), typeTranslator);
        HiveStorageFormat hiveStorageFormat = getHiveStorageFormat(tableMetadata.getProperties());
        ImmutableMap.Builder<String, String> additionalTableParameters = ImmutableMap.builder();
        additionalTableParameters.putAll(tableParameterCodec.encode(tableMetadata.getProperties()));
        tableMetadata.getComment().ifPresent(value -> additionalTableParameters.put(TABLE_COMMENT, value));

        hiveStorageFormat.validateColumns(columnHandles);

        Path targetPath;
        boolean external;
        String externalLocation = getExternalLocation(tableMetadata.getProperties());
        if (externalLocation != null) {
            external = true;
            targetPath = getExternalPath(session.getUser(), externalLocation);
        }
        else {
            external = false;
            LocationHandle locationHandle = locationService.forNewTable(metastore, session.getUser(), session.getQueryId(), schemaName, tableName);
            targetPath = locationService.targetPathRoot(locationHandle);
        }

        Table table = buildTableObject(
                session.getQueryId(),
                schemaName,
                tableName,
                session.getUser(),
                columnHandles,
                hiveStorageFormat,
                partitionedBy,
                bucketProperty,
                additionalTableParameters.build(),
                targetPath,
                external,
                prestoVersion);
        PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(table.getOwner());
        metastore.createTable(session, table, principalPrivileges, Optional.empty());
    }

    private Path getExternalPath(String user, String location)
    {
        try {
            Path path = new Path(location);
            if (!hdfsEnvironment.getFileSystem(user, path).isDirectory(path)) {
                throw new PrestoException(INVALID_TABLE_PROPERTY, "External location must be a directory");
            }
            return path;
        }
        catch (IllegalArgumentException | IOException e) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, "External location is not a valid file system URI", e);
        }
    }

    private static Table buildTableObject(
            String queryId,
            String schemaName,
            String tableName,
            String tableOwner,
            List<HiveColumnHandle> columnHandles,
            HiveStorageFormat hiveStorageFormat,
            List<String> partitionedBy,
            Optional<HiveBucketProperty> bucketProperty,
            Map<String, String> additionalTableParameters,
            Path targetPath,
            boolean external,
            String prestoVersion)
    {
        Map<String, HiveColumnHandle> columnHandlesByName = Maps.uniqueIndex(columnHandles, HiveColumnHandle::getName);
        List<Column> partitionColumns = partitionedBy.stream()
                .map(columnHandlesByName::get)
                .map(column -> new Column(column.getName(), column.getHiveType(), column.getComment()))
                .collect(toList());

        Set<String> partitionColumnNames = ImmutableSet.copyOf(partitionedBy);

        ImmutableList.Builder<Column> columns = ImmutableList.builder();
        for (HiveColumnHandle columnHandle : columnHandles) {
            String name = columnHandle.getName();
            HiveType type = columnHandle.getHiveType();
            if (!partitionColumnNames.contains(name)) {
                verify(!columnHandle.isPartitionKey(), "Column handles are not consistent with partitioned by property");
                columns.add(new Column(name, type, columnHandle.getComment()));
            }
            else {
                verify(columnHandle.isPartitionKey(), "Column handles are not consistent with partitioned by property");
            }
        }

        ImmutableMap.Builder<String, String> tableParameters = ImmutableMap.<String, String>builder()
                .put(PRESTO_VERSION_NAME, prestoVersion)
                .put(PRESTO_QUERY_ID_NAME, queryId)
                .putAll(additionalTableParameters);

        if (external) {
            tableParameters.put("EXTERNAL", "TRUE");
        }

        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(schemaName)
                .setTableName(tableName)
                .setOwner(tableOwner)
                .setTableType((external ? EXTERNAL_TABLE : MANAGED_TABLE).name())
                .setDataColumns(columns.build())
                .setPartitionColumns(partitionColumns)
                .setParameters(tableParameters.build());

        tableBuilder.getStorageBuilder()
                .setStorageFormat(fromHiveStorageFormat(hiveStorageFormat))
                .setBucketProperty(bucketProperty)
                .setLocation(targetPath.toString());

        return tableBuilder.build();
    }

    private static PrincipalPrivileges buildInitialPrivilegeSet(String tableOwner)
    {
        return new PrincipalPrivileges(
                ImmutableMultimap.<String, HivePrivilegeInfo>builder()
                        .put(tableOwner, new HivePrivilegeInfo(HivePrivilege.SELECT, true))
                        .put(tableOwner, new HivePrivilegeInfo(HivePrivilege.INSERT, true))
                        .put(tableOwner, new HivePrivilegeInfo(HivePrivilege.UPDATE, true))
                        .put(tableOwner, new HivePrivilegeInfo(HivePrivilege.DELETE, true))
                        .build(),
                ImmutableMultimap.of());
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        HiveTableHandle handle = (HiveTableHandle) tableHandle;

        metastore.addColumn(handle.getSchemaName(), handle.getTableName(), column.getName(), toHiveType(typeTranslator, column.getType()), column.getComment());
    }

    @Override
    public void renameColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle source, String target)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        HiveColumnHandle sourceHandle = (HiveColumnHandle) source;

        metastore.renameColumn(hiveTableHandle.getSchemaName(), hiveTableHandle.getTableName(), sourceHandle.getName(), target);
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        HiveColumnHandle columnHandle = (HiveColumnHandle) column;

        metastore.dropColumn(hiveTableHandle.getSchemaName(), hiveTableHandle.getTableName(), columnHandle.getName());
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTableName)
    {
        HiveTableHandle handle = (HiveTableHandle) tableHandle;
        metastore.renameTable(handle.getSchemaName(), handle.getTableName(), newTableName.getSchemaName(), newTableName.getTableName());
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        HiveTableHandle handle = (HiveTableHandle) tableHandle;
        SchemaTableName tableName = schemaTableName(tableHandle);

        Optional<Table> target = metastore.getTable(handle.getSchemaName(), handle.getTableName());
        if (!target.isPresent()) {
            throw new TableNotFoundException(tableName);
        }
        metastore.dropTable(session, handle.getSchemaName(), handle.getTableName());
    }

    @Override
    public HiveOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        verifyJvmTimeZone();

        if (getExternalLocation(tableMetadata.getProperties()) != null) {
            throw new PrestoException(NOT_SUPPORTED, "External tables cannot be created using CREATE TABLE AS");
        }

        HiveStorageFormat tableStorageFormat = getHiveStorageFormat(tableMetadata.getProperties());
        List<String> partitionedBy = getPartitionedBy(tableMetadata.getProperties());
        Optional<HiveBucketProperty> bucketProperty = getBucketProperty(tableMetadata.getProperties());

        ImmutableMap.Builder<String, String> additionalTableParameters = ImmutableMap.builder();
        additionalTableParameters.putAll(tableParameterCodec.encode(tableMetadata.getProperties()));
        tableMetadata.getComment().ifPresent(value -> additionalTableParameters.put(TABLE_COMMENT, value));

        // get the root directory for the database
        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        List<HiveColumnHandle> columnHandles = getColumnHandles(connectorId, tableMetadata, ImmutableSet.copyOf(partitionedBy), typeTranslator);
        HiveStorageFormat partitionStorageFormat = respectTableFormat ? tableStorageFormat : defaultStorageFormat;

        // unpartitioned tables ignore the partition storage format
        HiveStorageFormat actualStorageFormat = partitionedBy.isEmpty() ? tableStorageFormat : partitionStorageFormat;
        actualStorageFormat.validateColumns(columnHandles);

        LocationHandle locationHandle = locationService.forNewTable(metastore, session.getUser(), session.getQueryId(), schemaName, tableName);
        HiveOutputTableHandle result = new HiveOutputTableHandle(
                connectorId,
                schemaName,
                tableName,
                columnHandles,
                session.getQueryId(),
                metastore.generatePageSinkMetadata(schemaTableName),
                locationHandle,
                tableStorageFormat,
                partitionStorageFormat,
                partitionedBy,
                bucketProperty,
                session.getUser(),
                additionalTableParameters.build());

        Path writePathRoot = locationService.writePathRoot(locationHandle).get();
        Path targetPathRoot = locationService.targetPathRoot(locationHandle);
        WriteMode mode = writePathRoot.equals(targetPathRoot) ? DIRECT_TO_TARGET_NEW_DIRECTORY : STAGE_AND_MOVE_TO_TARGET_DIRECTORY;
        metastore.declareIntentionToWrite(session, mode, writePathRoot, result.getFilePrefix(), schemaTableName);

        return result;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments)
    {
        HiveOutputTableHandle handle = (HiveOutputTableHandle) tableHandle;

        List<PartitionUpdate> partitionUpdates = fragments.stream()
                .map(Slice::getBytes)
                .map(partitionUpdateCodec::fromJson)
                .collect(toList());

        Path targetPath = locationService.targetPathRoot(handle.getLocationHandle());
        Path writePath = locationService.writePathRoot(handle.getLocationHandle()).get();

        Table table = buildTableObject(
                session.getQueryId(),
                handle.getSchemaName(),
                handle.getTableName(),
                handle.getTableOwner(),
                handle.getInputColumns(),
                handle.getTableStorageFormat(),
                handle.getPartitionedBy(),
                handle.getBucketProperty(),
                handle.getAdditionalTableParameters(),
                targetPath,
                false,
                prestoVersion);
        PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(handle.getTableOwner());

        partitionUpdates = PartitionUpdate.mergePartitionUpdates(partitionUpdates);

        if (handle.getBucketProperty().isPresent()) {
            ImmutableList<PartitionUpdate> partitionUpdatesForMissingBuckets = computePartitionUpdatesForMissingBuckets(handle, table, partitionUpdates);
            // replace partitionUpdates before creating the empty files so that those files will be cleaned up if we end up rollback
            partitionUpdates = PartitionUpdate.mergePartitionUpdates(Iterables.concat(partitionUpdates, partitionUpdatesForMissingBuckets));
            for (PartitionUpdate partitionUpdate : partitionUpdatesForMissingBuckets) {
                Optional<Partition> partition = table.getPartitionColumns().isEmpty() ? Optional.empty() : Optional.of(buildPartitionObject(session.getQueryId(), table, partitionUpdate));
                createEmptyFile(partitionUpdate.getWritePath(), table, partition, partitionUpdate.getFileNames());
            }
        }

        metastore.createTable(session, table, principalPrivileges, Optional.of(writePath));

        if (!handle.getPartitionedBy().isEmpty()) {
            if (respectTableFormat) {
                Verify.verify(handle.getPartitionStorageFormat() == handle.getTableStorageFormat());
            }
            partitionUpdates.forEach(partitionUpdate ->
                    metastore.addPartition(session, handle.getSchemaName(), handle.getTableName(), buildPartitionObject(session.getQueryId(), table, partitionUpdate), partitionUpdate.getWritePath()));
        }

        return Optional.of(new HiveWrittenPartitions(
                partitionUpdates.stream()
                        .map(PartitionUpdate::getName)
                        .collect(Collectors.toList())));
    }

    private ImmutableList<PartitionUpdate> computePartitionUpdatesForMissingBuckets(HiveWritableTableHandle handle, Table table, List<PartitionUpdate> partitionUpdates)
    {
        ImmutableList.Builder<PartitionUpdate> partitionUpdatesForMissingBucketsBuilder = ImmutableList.builder();
        HiveStorageFormat storageFormat = table.getPartitionColumns().isEmpty() ? handle.getTableStorageFormat() : handle.getPartitionStorageFormat();
        for (PartitionUpdate partitionUpdate : partitionUpdates) {
            int bucketCount = handle.getBucketProperty().get().getBucketCount();

            List<String> fileNamesForMissingBuckets = computeFileNamesForMissingBuckets(
                    storageFormat,
                    partitionUpdate.getTargetPath(),
                    handle.getFilePrefix(),
                    bucketCount,
                    partitionUpdate);
            partitionUpdatesForMissingBucketsBuilder.add(new PartitionUpdate(
                    partitionUpdate.getName(),
                    partitionUpdate.isNew(),
                    partitionUpdate.getWritePath(),
                    partitionUpdate.getTargetPath(),
                    fileNamesForMissingBuckets));
        }
        return partitionUpdatesForMissingBucketsBuilder.build();
    }

    private List<String> computeFileNamesForMissingBuckets(HiveStorageFormat storageFormat, Path targetPath, String filePrefix, int bucketCount, PartitionUpdate partitionUpdate)
    {
        if (partitionUpdate.getFileNames().size() == bucketCount) {
            // fast path for common case
            return ImmutableList.of();
        }
        JobConf conf = toJobConf(hdfsEnvironment.getConfiguration(targetPath));
        String fileExtension = HiveWriterFactory.getFileExtension(conf, fromHiveStorageFormat(storageFormat));
        Set<String> fileNames = ImmutableSet.copyOf(partitionUpdate.getFileNames());
        ImmutableList.Builder<String> missingFileNamesBuilder = ImmutableList.builder();
        for (int i = 0; i < bucketCount; i++) {
            String fileName = HiveWriterFactory.computeBucketedFileName(filePrefix, i) + fileExtension;
            if (!fileNames.contains(fileName)) {
                missingFileNamesBuilder.add(fileName);
            }
        }
        List<String> missingFileNames = missingFileNamesBuilder.build();
        verify(fileNames.size() + missingFileNames.size() == bucketCount);
        return missingFileNames;
    }

    private void createEmptyFile(Path path, Table table, Optional<Partition> partition, List<String> fileNames)
    {
        JobConf conf = toJobConf(hdfsEnvironment.getConfiguration(path));

        Properties schema;
        StorageFormat format;
        if (partition.isPresent()) {
            schema = getHiveSchema(partition.get(), table);
            format = partition.get().getStorage().getStorageFormat();
        }
        else {
            schema = getHiveSchema(table);
            format = table.getStorage().getStorageFormat();
        }

        for (String fileName : fileNames) {
            writeEmptyFile(new Path(path, fileName), conf, schema, format.getSerDe(), format.getOutputFormat());
        }
    }

    private static void writeEmptyFile(Path target, JobConf conf, Properties properties, String serDe, String outputFormatName)
    {
        // Some serializers such as Avro set a property in the schema.
        initializeSerializer(conf, properties, serDe);

        // The code below is not a try with resources because RecordWriter is not Closeable.
        FileSinkOperator.RecordWriter recordWriter = HiveWriteUtils.createRecordWriter(target, conf, properties, outputFormatName);
        try {
            recordWriter.close(false);
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_WRITER_CLOSE_ERROR, "Error write empty file to Hive", e);
        }
    }

    @Override
    public HiveInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        verifyJvmTimeZone();

        SchemaTableName tableName = schemaTableName(tableHandle);
        Optional<Table> table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (!table.isPresent()) {
            throw new TableNotFoundException(tableName);
        }

        checkTableIsWritable(table.get(), writesToNonManagedTablesEnabled);

        for (Column column : table.get().getDataColumns()) {
            if (!isWritableType(column.getType())) {
                throw new PrestoException(
                        NOT_SUPPORTED,
                        format("Inserting into Hive table %s.%s with column type %s not supported", table.get().getDatabaseName(), table.get().getTableName(), column.getType()));
            }
        }

        List<HiveColumnHandle> handles = hiveColumnHandles(connectorId, table.get()).stream()
                .filter(columnHandle -> !columnHandle.isHidden())
                .collect(toList());

        HiveStorageFormat tableStorageFormat = extractHiveStorageFormat(table.get());
        LocationHandle locationHandle = locationService.forExistingTable(metastore, session.getUser(), session.getQueryId(), table.get());
        HiveInsertTableHandle result = new HiveInsertTableHandle(
                connectorId,
                tableName.getSchemaName(),
                tableName.getTableName(),
                handles,
                session.getQueryId(),
                metastore.generatePageSinkMetadata(tableName),
                locationHandle,
                table.get().getStorage().getBucketProperty(),
                tableStorageFormat,
                respectTableFormat ? tableStorageFormat : defaultStorageFormat);

        Optional<Path> writePathRoot = locationService.writePathRoot(locationHandle);
        Path targetPathRoot = locationService.targetPathRoot(locationHandle);
        if (writePathRoot.isPresent()) {
            WriteMode mode = writePathRoot.get().equals(targetPathRoot) ? DIRECT_TO_TARGET_NEW_DIRECTORY : STAGE_AND_MOVE_TO_TARGET_DIRECTORY;
            metastore.declareIntentionToWrite(session, mode, writePathRoot.get(), result.getFilePrefix(), tableName);
        }
        else {
            metastore.declareIntentionToWrite(session, DIRECT_TO_TARGET_EXISTING_DIRECTORY, targetPathRoot, result.getFilePrefix(), tableName);
        }

        return result;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments)
    {
        HiveInsertTableHandle handle = (HiveInsertTableHandle) insertHandle;

        List<PartitionUpdate> partitionUpdates = fragments.stream()
                .map(Slice::getBytes)
                .map(partitionUpdateCodec::fromJson)
                .collect(toList());

        HiveStorageFormat tableStorageFormat = handle.getTableStorageFormat();
        partitionUpdates = PartitionUpdate.mergePartitionUpdates(partitionUpdates);

        Optional<Table> table = metastore.getTable(handle.getSchemaName(), handle.getTableName());
        if (!table.isPresent()) {
            throw new TableNotFoundException(new SchemaTableName(handle.getSchemaName(), handle.getTableName()));
        }
        if (!table.get().getStorage().getStorageFormat().getInputFormat().equals(tableStorageFormat.getInputFormat()) && respectTableFormat) {
            throw new PrestoException(HIVE_CONCURRENT_MODIFICATION_DETECTED, "Table format changed during insert");
        }

        if (handle.getBucketProperty().isPresent()) {
            ImmutableList<PartitionUpdate> partitionUpdatesForMissingBuckets = computePartitionUpdatesForMissingBuckets(handle, table.get(), partitionUpdates);
            // replace partitionUpdates before creating the empty files so that those files will be cleaned up if we end up rollback
            partitionUpdates = PartitionUpdate.mergePartitionUpdates(Iterables.concat(partitionUpdates, partitionUpdatesForMissingBuckets));
            for (PartitionUpdate partitionUpdate : partitionUpdatesForMissingBuckets) {
                Optional<Partition> partition = table.get().getPartitionColumns().isEmpty() ? Optional.empty() : Optional.of(buildPartitionObject(session.getQueryId(), table.get(), partitionUpdate));
                createEmptyFile(partitionUpdate.getWritePath(), table.get(), partition, partitionUpdate.getFileNames());
            }
        }

        for (PartitionUpdate partitionUpdate : partitionUpdates) {
            if (partitionUpdate.getName().isEmpty()) {
                // insert into unpartitioned table
                metastore.finishInsertIntoExistingTable(
                        session,
                        handle.getSchemaName(),
                        handle.getTableName(),
                        partitionUpdate.getWritePath(),
                        partitionUpdate.getFileNames());
            }
            else if (!partitionUpdate.isNew()) {
                // insert into existing partition
                metastore.finishInsertIntoExistingPartition(
                        session,
                        handle.getSchemaName(),
                        handle.getTableName(),
                        toPartitionValues(partitionUpdate.getName()),
                        partitionUpdate.getWritePath(),
                        partitionUpdate.getFileNames());
            }
            else {
                // insert into new partition
                Partition partition = buildPartitionObject(session.getQueryId(), table.get(), partitionUpdate);
                if (!partition.getStorage().getStorageFormat().getInputFormat().equals(handle.getPartitionStorageFormat().getInputFormat()) && respectTableFormat) {
                    throw new PrestoException(HIVE_CONCURRENT_MODIFICATION_DETECTED, "Partition format changed during insert");
                }
                metastore.addPartition(session, handle.getSchemaName(), handle.getTableName(), partition, partitionUpdate.getWritePath());
            }
        }

        return Optional.of(new HiveWrittenPartitions(
                partitionUpdates.stream()
                        .map(PartitionUpdate::getName)
                        .collect(Collectors.toList())));
    }

    private Partition buildPartitionObject(String queryId, Table table, PartitionUpdate partitionUpdate)
    {
        return Partition.builder()
                .setDatabaseName(table.getDatabaseName())
                .setTableName(table.getTableName())
                .setColumns(table.getDataColumns())
                .setValues(extractPartitionKeyValues(partitionUpdate.getName()))
                .setParameters(ImmutableMap.<String, String>builder()
                        .put(PRESTO_VERSION_NAME, prestoVersion)
                        .put(PRESTO_QUERY_ID_NAME, queryId)
                        .build())
                .withStorage(storage -> storage
                        .setStorageFormat(respectTableFormat ?
                                table.getStorage().getStorageFormat() :
                                fromHiveStorageFormat(defaultStorageFormat))
                        .setLocation(partitionUpdate.getTargetPath().toString())
                        .setBucketProperty(table.getStorage().getBucketProperty()))
                .build();
    }

    @Override
    public void createView(ConnectorSession session, SchemaTableName viewName, String viewData, boolean replace)
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put(TABLE_COMMENT, "Presto View")
                .put(PRESTO_VIEW_FLAG, "true")
                .put(PRESTO_VERSION_NAME, prestoVersion)
                .put(PRESTO_QUERY_ID_NAME, session.getQueryId())
                .build();

        Column dummyColumn = new Column("dummy", HIVE_STRING, Optional.empty());

        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(viewName.getSchemaName())
                .setTableName(viewName.getTableName())
                .setOwner(session.getUser())
                .setTableType(TableType.VIRTUAL_VIEW.name())
                .setDataColumns(ImmutableList.of(dummyColumn))
                .setPartitionColumns(ImmutableList.of())
                .setParameters(properties)
                .setViewOriginalText(Optional.of(encodeViewData(viewData)))
                .setViewExpandedText(Optional.of("/* Presto View */"));

        tableBuilder.getStorageBuilder()
                .setStorageFormat(VIEW_STORAGE_FORMAT)
                .setLocation("");
        Table table = tableBuilder.build();
        PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(session.getUser());

        Optional<Table> existing = metastore.getTable(viewName.getSchemaName(), viewName.getTableName());
        if (existing.isPresent()) {
            if (!replace || !HiveUtil.isPrestoView(existing.get())) {
                throw new ViewAlreadyExistsException(viewName);
            }

            metastore.replaceView(viewName.getSchemaName(), viewName.getTableName(), table, principalPrivileges);
            return;
        }

        try {
            metastore.createTable(session, table, principalPrivileges, Optional.empty());
        }
        catch (TableAlreadyExistsException e) {
            throw new ViewAlreadyExistsException(e.getTableName());
        }
    }

    @Override
    public void dropView(ConnectorSession session, SchemaTableName viewName)
    {
        ConnectorViewDefinition view = getViews(session, viewName.toSchemaTablePrefix()).get(viewName);
        if (view == null) {
            throw new ViewNotFoundException(viewName);
        }

        try {
            metastore.dropTable(session, viewName.getSchemaName(), viewName.getTableName());
        }
        catch (TableNotFoundException e) {
            throw new ViewNotFoundException(e.getTableName());
        }
    }

    @Override
    public List<SchemaTableName> listViews(ConnectorSession session, String schemaNameOrNull)
    {
        ImmutableList.Builder<SchemaTableName> tableNames = ImmutableList.builder();
        for (String schemaName : listSchemas(session, schemaNameOrNull)) {
            for (String tableName : metastore.getAllViews(schemaName).orElse(emptyList())) {
                tableNames.add(new SchemaTableName(schemaName, tableName));
            }
        }
        return tableNames.build();
    }

    @Override
    public Map<SchemaTableName, ConnectorViewDefinition> getViews(ConnectorSession session, SchemaTablePrefix prefix)
    {
        ImmutableMap.Builder<SchemaTableName, ConnectorViewDefinition> views = ImmutableMap.builder();
        List<SchemaTableName> tableNames;
        if (prefix.getTableName() != null) {
            tableNames = ImmutableList.of(new SchemaTableName(prefix.getSchemaName(), prefix.getTableName()));
        }
        else {
            tableNames = listViews(session, prefix.getSchemaName());
        }

        for (SchemaTableName schemaTableName : tableNames) {
            Optional<Table> table = metastore.getTable(schemaTableName.getSchemaName(), schemaTableName.getTableName());
            if (table.isPresent() && HiveUtil.isPrestoView(table.get())) {
                views.put(schemaTableName, new ConnectorViewDefinition(
                        schemaTableName,
                        Optional.ofNullable(table.get().getOwner()),
                        decodeViewData(table.get().getViewOriginalText().get())));
            }
        }

        return views.build();
    }

    @Override
    public ConnectorTableHandle beginDelete(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new PrestoException(NOT_SUPPORTED, "This connector only supports delete where one or more partitions are deleted entirely");
    }

    @Override
    public ColumnHandle getUpdateRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return updateRowIdHandle(connectorId);
    }

    @Override
    public OptionalLong metadataDelete(ConnectorSession session, ConnectorTableHandle tableHandle, ConnectorTableLayoutHandle tableLayoutHandle)
    {
        HiveTableHandle handle = (HiveTableHandle) tableHandle;
        HiveTableLayoutHandle layoutHandle = (HiveTableLayoutHandle) tableLayoutHandle;

        Optional<Table> table = metastore.getTable(handle.getSchemaName(), handle.getTableName());
        if (!table.isPresent()) {
            throw new TableNotFoundException(handle.getSchemaTableName());
        }

        if (table.get().getPartitionColumns().isEmpty()) {
            metastore.truncateUnpartitionedTable(session, handle.getSchemaName(), handle.getTableName());
        }
        else {
            for (HivePartition hivePartition : getOrComputePartitions(layoutHandle, session, tableHandle)) {
                metastore.dropPartition(session, handle.getSchemaName(), handle.getTableName(), toPartitionValues(hivePartition.getPartitionId()));
            }
        }
        // it is too expensive to determine the exact number of deleted rows
        return OptionalLong.empty();
    }

    private List<HivePartition> getOrComputePartitions(HiveTableLayoutHandle layoutHandle, ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        if (layoutHandle.getPartitions().isPresent()) {
            return layoutHandle.getPartitions().get();
        }
        else {
            TupleDomain<ColumnHandle> promisedPredicate = layoutHandle.getPromisedPredicate();
            Predicate<Map<ColumnHandle, NullableValue>> predicate = convertToPredicate(promisedPredicate);
            List<ConnectorTableLayoutResult> tableLayoutResults = getTableLayouts(session, tableHandle, new Constraint<>(promisedPredicate, predicate), Optional.empty());
            return ((HiveTableLayoutHandle) Iterables.getOnlyElement(tableLayoutResults).getTableLayout().getHandle()).getPartitions().get();
        }
    }

    @VisibleForTesting
    static Predicate<Map<ColumnHandle, NullableValue>> convertToPredicate(TupleDomain<ColumnHandle> tupleDomain)
    {
        return bindings -> tupleDomain.contains(TupleDomain.fromFixedValues(bindings));
    }

    @Override
    public boolean supportsMetadataDelete(ConnectorSession session, ConnectorTableHandle tableHandle, ConnectorTableLayoutHandle tableLayoutHandle)
    {
        return true;
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint, Optional<Set<ColumnHandle>> desiredColumns)
    {
        HiveTableHandle handle = (HiveTableHandle) tableHandle;

        HivePartitionResult hivePartitionResult = partitionManager.getPartitions(metastore, tableHandle, constraint);

        return ImmutableList.of(new ConnectorTableLayoutResult(
                getTableLayout(
                        session,
                        new HiveTableLayoutHandle(
                                handle.getClientId(),
                                ImmutableList.copyOf(hivePartitionResult.getPartitionColumns()),
                                hivePartitionResult.getPartitions(),
                                hivePartitionResult.getEnforcedConstraint(),
                                hivePartitionResult.getBucketHandle())),
                hivePartitionResult.getUnenforcedConstraint()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle layoutHandle)
    {
        HiveTableLayoutHandle hiveLayoutHandle = (HiveTableLayoutHandle) layoutHandle;
        List<ColumnHandle> partitionColumns = hiveLayoutHandle.getPartitionColumns();
        List<HivePartition> partitions = hiveLayoutHandle.getPartitions().get();

        TupleDomain<ColumnHandle> predicate = createPredicate(partitionColumns, partitions);

        Optional<DiscretePredicates> discretePredicates = Optional.empty();
        if (!partitionColumns.isEmpty()) {
            // Do not create tuple domains for every partition at the same time!
            // There can be a huge number of partitions so use an iterable so
            // all domains do not need to be in memory at the same time.
            Iterable<TupleDomain<ColumnHandle>> partitionDomains = Iterables.transform(partitions, (hivePartition) -> TupleDomain.fromFixedValues(hivePartition.getKeys()));
            discretePredicates = Optional.of(new DiscretePredicates(partitionColumns, partitionDomains));
        }

        Optional<ConnectorNodePartitioning> nodePartitioning = Optional.empty();
        if (isBucketExecutionEnabled(session) && hiveLayoutHandle.getBucketHandle().isPresent()) {
            nodePartitioning = hiveLayoutHandle.getBucketHandle().map(hiveBucketHandle -> new ConnectorNodePartitioning(
                    new HivePartitioningHandle(
                            connectorId,
                            hiveBucketHandle.getBucketCount(),
                            hiveBucketHandle.getColumns().stream()
                                    .map(HiveColumnHandle::getHiveType)
                                    .collect(Collectors.toList())),
                    hiveBucketHandle.getColumns().stream()
                            .map(ColumnHandle.class::cast)
                            .collect(toList())));
        }

        return new ConnectorTableLayout(
                hiveLayoutHandle,
                Optional.empty(),
                predicate,
                nodePartitioning,
                Optional.empty(),
                discretePredicates,
                ImmutableList.of());
    }

    @VisibleForTesting
    static TupleDomain<ColumnHandle> createPredicate(List<ColumnHandle> partitionColumns, List<HivePartition> partitions)
    {
        if (partitions.isEmpty()) {
            return TupleDomain.none();
        }

        return withColumnDomains(
                partitionColumns.stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                column -> buildColumnDomain(column, partitions))));
    }

    private static Domain buildColumnDomain(ColumnHandle column, List<HivePartition> partitions)
    {
        checkArgument(!partitions.isEmpty(), "partitions cannot be empty");

        boolean hasNull = false;
        List<Object> nonNullValues = new ArrayList<>();
        Type type = null;

        for (HivePartition partition : partitions) {
            NullableValue value = partition.getKeys().get(column);
            if (value == null) {
                throw new PrestoException(HIVE_UNKNOWN_ERROR, format("Partition %s does not have a value for partition column %s", partition, column));
            }

            if (value.isNull()) {
                hasNull = true;
            }
            else {
                nonNullValues.add(value.getValue());
            }

            if (type == null) {
                type = value.getType();
            }
        }

        if (!nonNullValues.isEmpty()) {
            Domain domain = Domain.multipleValues(type, nonNullValues);
            if (hasNull) {
                return domain.union(Domain.onlyNull(type));
            }

            return domain;
        }

        return Domain.onlyNull(type);
    }

    @Override
    public Optional<ConnectorNewTableLayout> getInsertLayout(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        SchemaTableName tableName = hiveTableHandle.getSchemaTableName();
        Table table = metastore.getTable(tableName.getSchemaName(), tableName.getTableName())
                .orElseThrow(() -> new TableNotFoundException(tableName));

        Optional<HiveBucketHandle> hiveBucketHandle = getHiveBucketHandle(connectorId, table);
        if (!hiveBucketHandle.isPresent()) {
            return Optional.empty();
        }
        if (!bucketWritingEnabled) {
            throw new PrestoException(NOT_SUPPORTED, "Writing to bucketed Hive table has been temporarily disabled");
        }
        HivePartitioningHandle partitioningHandle = new HivePartitioningHandle(
                connectorId,
                hiveBucketHandle.get().getBucketCount(),
                hiveBucketHandle.get().getColumns().stream()
                        .map(HiveColumnHandle::getHiveType)
                        .collect(Collectors.toList()));
        List<String> partitionColumns = hiveBucketHandle.get().getColumns().stream()
                .map(HiveColumnHandle::getName)
                .collect(Collectors.toList());
        return Optional.of(new ConnectorNewTableLayout(partitioningHandle, partitionColumns));
    }

    @Override
    public Optional<ConnectorNewTableLayout> getNewTableLayout(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        Optional<HiveBucketProperty> bucketProperty = getBucketProperty(tableMetadata.getProperties());
        if (!bucketProperty.isPresent()) {
            return Optional.empty();
        }
        if (!bucketWritingEnabled) {
            throw new PrestoException(NOT_SUPPORTED, "Writing to bucketed Hive table has been temporarily disabled");
        }
        List<String> bucketedBy = bucketProperty.get().getBucketedBy();
        Map<String, HiveType> hiveTypeMap = tableMetadata.getColumns().stream()
                .collect(toMap(ColumnMetadata::getName, column -> toHiveType(typeTranslator, column.getType())));
        return Optional.of(new ConnectorNewTableLayout(
                new HivePartitioningHandle(
                        connectorId,
                        bucketProperty.get().getBucketCount(),
                        bucketedBy.stream()
                                .map(hiveTypeMap::get)
                                .collect(toList())),
                bucketedBy));
    }

    @Override
    public void grantTablePrivileges(ConnectorSession session, SchemaTableName schemaTableName, Set<Privilege> privileges, String grantee, boolean grantOption)
    {
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        Set<HivePrivilegeInfo> hivePrivilegeInfos = privileges.stream()
                .map(privilege -> new HivePrivilegeInfo(toHivePrivilege(privilege), grantOption))
                .collect(toSet());

        metastore.grantTablePrivileges(schemaName, tableName, grantee, hivePrivilegeInfos);
    }

    @Override
    public void revokeTablePrivileges(ConnectorSession session, SchemaTableName schemaTableName, Set<Privilege> privileges, String grantee, boolean grantOption)
    {
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        Set<HivePrivilegeInfo> hivePrivilegeInfos = privileges.stream()
                .map(privilege -> new HivePrivilegeInfo(toHivePrivilege(privilege), grantOption))
                .collect(toSet());

        metastore.revokeTablePrivileges(schemaName, tableName, grantee, hivePrivilegeInfos);
    }

    @Override
    public List<GrantInfo> listTablePrivileges(ConnectorSession session, SchemaTablePrefix schemaTablePrefix)
    {
        ImmutableList.Builder<GrantInfo> grantInfos = ImmutableList.builder();
        for (SchemaTableName tableName : listTables(session, schemaTablePrefix)) {
            Set<PrivilegeInfo> privileges = metastore.getTablePrivileges(session.getUser(), tableName.getSchemaName(), tableName.getTableName()).stream()
                    .map(HivePrivilegeInfo::toPrivilegeInfo)
                    .flatMap(Set::stream)
                    .collect(toImmutableSet());

            grantInfos.add(
                    new GrantInfo(
                            privileges,
                            session.getIdentity(),
                            tableName,
                            Optional.empty(), // Can't access grantor
                            Optional.empty())); // Can't access withHierarchy
        }
        return grantInfos.build();
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("clientId", connectorId)
                .toString();
    }

    private void verifyJvmTimeZone()
    {
        if (!allowCorruptWritesForTesting && !timeZone.equals(DateTimeZone.getDefault())) {
            throw new PrestoException(HIVE_TIMEZONE_MISMATCH, format(
                    "To write Hive data, your JVM timezone must match the Hive storage timezone. Add -Duser.timezone=%s to your JVM arguments.",
                    timeZone.getID()));
        }
    }

    private static HiveStorageFormat extractHiveStorageFormat(Table table)
    {
        StorageFormat storageFormat = table.getStorage().getStorageFormat();
        String outputFormat = storageFormat.getOutputFormat();
        String serde = storageFormat.getSerDe();

        for (HiveStorageFormat format : HiveStorageFormat.values()) {
            if (format.getOutputFormat().equals(outputFormat) && format.getSerDe().equals(serde)) {
                return format;
            }
        }
        throw new PrestoException(HIVE_UNSUPPORTED_FORMAT, format("Output format %s with SerDe %s is not supported", outputFormat, serde));
    }

    private static void validateBucketColumns(ConnectorTableMetadata tableMetadata)
    {
        Optional<HiveBucketProperty> bucketProperty = getBucketProperty(tableMetadata.getProperties());
        if (!bucketProperty.isPresent()) {
            return;
        }
        Set<String> allColumns = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getName)
                .collect(toSet());
        List<String> bucketedBy = bucketProperty.get().getBucketedBy();
        if (!allColumns.containsAll(bucketedBy)) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, format("Bucketing columns %s not present in schema", Sets.difference(ImmutableSet.copyOf(bucketedBy), ImmutableSet.copyOf(allColumns))));
        }
    }

    private static void validatePartitionColumns(ConnectorTableMetadata tableMetadata)
    {
        List<String> partitionedBy = getPartitionedBy(tableMetadata.getProperties());

        List<String> allColumns = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getName)
                .collect(toList());

        if (!allColumns.containsAll(partitionedBy)) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, format("Partition columns %s not present in schema", Sets.difference(ImmutableSet.copyOf(partitionedBy), ImmutableSet.copyOf(allColumns))));
        }

        if (allColumns.size() == partitionedBy.size()) {
            throw new PrestoException(INVALID_TABLE_PROPERTY, "Table contains only partition columns");
        }

        if (!allColumns.subList(allColumns.size() - partitionedBy.size(), allColumns.size()).equals(partitionedBy)) {
            throw new PrestoException(HIVE_COLUMN_ORDER_MISMATCH, "Partition keys must be the last columns in the table and in the same order as the table properties: " + partitionedBy);
        }
    }

    private static List<HiveColumnHandle> getColumnHandles(String connectorId, ConnectorTableMetadata tableMetadata, Set<String> partitionColumnNames, TypeTranslator typeTranslator)
    {
        validatePartitionColumns(tableMetadata);
        validateBucketColumns(tableMetadata);

        ImmutableList.Builder<HiveColumnHandle> columnHandles = ImmutableList.builder();
        int ordinal = 0;
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            HiveColumnHandle.ColumnType columnType;
            if (partitionColumnNames.contains(column.getName())) {
                columnType = PARTITION_KEY;
            }
            else if (column.isHidden()) {
                columnType = HIDDEN;
            }
            else {
                columnType = REGULAR;
            }
            columnHandles.add(new HiveColumnHandle(
                    connectorId,
                    column.getName(),
                    toHiveType(typeTranslator, column.getType()),
                    column.getType().getTypeSignature(),
                    ordinal,
                    columnType,
                    Optional.ofNullable(column.getComment())));
            ordinal++;
        }

        return columnHandles.build();
    }

    private static Function<HiveColumnHandle, ColumnMetadata> columnMetadataGetter(Table table, TypeManager typeManager)
    {
        ImmutableList.Builder<String> columnNames = ImmutableList.builder();
        table.getPartitionColumns().stream().map(Column::getName).forEach(columnNames::add);
        table.getDataColumns().stream().map(Column::getName).forEach(columnNames::add);
        List<String> allColumnNames = columnNames.build();
        if (allColumnNames.size() > Sets.newHashSet(allColumnNames).size()) {
            throw new PrestoException(HIVE_INVALID_METADATA,
                    format("Hive metadata for table %s is invalid: Table descriptor contains duplicate columns", table.getTableName()));
        }

        List<Column> tableColumns = table.getDataColumns();
        ImmutableMap.Builder<String, Optional<String>> builder = ImmutableMap.builder();
        for (Column field : concat(tableColumns, table.getPartitionColumns())) {
            if ((field.getComment() != null) && !Optional.of("from deserializer").equals(field.getComment())) {
                builder.put(field.getName(), field.getComment());
            }
            else {
                builder.put(field.getName(), Optional.empty());
            }
        }
        // add hidden columns
        builder.put(PATH_COLUMN_NAME, Optional.empty());
        if (table.getStorage().getBucketProperty().isPresent()) {
            builder.put(BUCKET_COLUMN_NAME, Optional.empty());
        }

        Map<String, Optional<String>> columnComment = builder.build();

        return handle -> new ColumnMetadata(
                handle.getName(),
                typeManager.getType(handle.getTypeSignature()),
                columnComment.get(handle.getName()).orElse(null),
                columnExtraInfo(handle.isPartitionKey()),
                handle.isHidden());
    }

    public void rollback()
    {
        metastore.rollback();
    }

    public void commit()
    {
        metastore.commit();
    }
}
