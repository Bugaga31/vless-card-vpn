package com.vlesscardvpn.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile VlessConfigDao _vlessConfigDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `vless_configs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `protocolType` TEXT NOT NULL, `flow` TEXT NOT NULL, `security` TEXT NOT NULL, `sni` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `shortId` TEXT NOT NULL, `remark` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `pingMs` INTEGER NOT NULL, `isFree` INTEGER NOT NULL, `country` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `lastCheck` INTEGER NOT NULL, `healthState` TEXT NOT NULL, `failureCount` INTEGER NOT NULL, `source` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `tcpLatencyMs` INTEGER NOT NULL, `tlsLatencyMs` INTEGER NOT NULL, `httpLatencyMs` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '2eb6e84b06b9c90d5a7e53095c8bc5fd')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `vless_configs`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsVlessConfigs = new HashMap<String, TableInfo.Column>(26);
        _columnsVlessConfigs.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("address", new TableInfo.Column("address", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("port", new TableInfo.Column("port", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("uuid", new TableInfo.Column("uuid", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("protocolType", new TableInfo.Column("protocolType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("flow", new TableInfo.Column("flow", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("security", new TableInfo.Column("security", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("sni", new TableInfo.Column("sni", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("fingerprint", new TableInfo.Column("fingerprint", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("publicKey", new TableInfo.Column("publicKey", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("shortId", new TableInfo.Column("shortId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("remark", new TableInfo.Column("remark", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("isActive", new TableInfo.Column("isActive", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("pingMs", new TableInfo.Column("pingMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("isFree", new TableInfo.Column("isFree", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("country", new TableInfo.Column("country", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("addedAt", new TableInfo.Column("addedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("lastCheck", new TableInfo.Column("lastCheck", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("healthState", new TableInfo.Column("healthState", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("failureCount", new TableInfo.Column("failureCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("source", new TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("isFavorite", new TableInfo.Column("isFavorite", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("tcpLatencyMs", new TableInfo.Column("tcpLatencyMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("tlsLatencyMs", new TableInfo.Column("tlsLatencyMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsVlessConfigs.put("httpLatencyMs", new TableInfo.Column("httpLatencyMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysVlessConfigs = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesVlessConfigs = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoVlessConfigs = new TableInfo("vless_configs", _columnsVlessConfigs, _foreignKeysVlessConfigs, _indicesVlessConfigs);
        final TableInfo _existingVlessConfigs = TableInfo.read(db, "vless_configs");
        if (!_infoVlessConfigs.equals(_existingVlessConfigs)) {
          return new RoomOpenHelper.ValidationResult(false, "vless_configs(com.vlesscardvpn.data.db.VlessConfigEntity).\n"
                  + " Expected:\n" + _infoVlessConfigs + "\n"
                  + " Found:\n" + _existingVlessConfigs);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "2eb6e84b06b9c90d5a7e53095c8bc5fd", "fa138ac5d14cf8ee136640c5f4d0cca5");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "vless_configs");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `vless_configs`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(VlessConfigDao.class, VlessConfigDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public VlessConfigDao vlessConfigDao() {
    if (_vlessConfigDao != null) {
      return _vlessConfigDao;
    } else {
      synchronized(this) {
        if(_vlessConfigDao == null) {
          _vlessConfigDao = new VlessConfigDao_Impl(this);
        }
        return _vlessConfigDao;
      }
    }
  }
}
