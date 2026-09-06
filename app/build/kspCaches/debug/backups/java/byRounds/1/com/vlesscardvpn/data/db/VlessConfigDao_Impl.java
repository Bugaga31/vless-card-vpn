package com.vlesscardvpn.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class VlessConfigDao_Impl implements VlessConfigDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<VlessConfigEntity> __insertionAdapterOfVlessConfigEntity;

  private final EntityDeletionOrUpdateAdapter<VlessConfigEntity> __updateAdapterOfVlessConfigEntity;

  private final SharedSQLiteStatement __preparedStmtOfSetActive;

  private final SharedSQLiteStatement __preparedStmtOfClearActive;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  private final SharedSQLiteStatement __preparedStmtOfClearFreeNodes;

  private final SharedSQLiteStatement __preparedStmtOfClearAll;

  public VlessConfigDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfVlessConfigEntity = new EntityInsertionAdapter<VlessConfigEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `vless_configs` (`id`,`name`,`address`,`port`,`uuid`,`protocolType`,`flow`,`security`,`sni`,`fingerprint`,`publicKey`,`shortId`,`remark`,`isActive`,`pingMs`,`isFree`,`country`,`addedAt`,`lastCheck`,`healthState`,`failureCount`,`source`,`isFavorite`,`tcpLatencyMs`,`tlsLatencyMs`,`httpLatencyMs`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final VlessConfigEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getAddress());
        statement.bindLong(4, entity.getPort());
        statement.bindString(5, entity.getUuid());
        statement.bindString(6, entity.getProtocolType());
        statement.bindString(7, entity.getFlow());
        statement.bindString(8, entity.getSecurity());
        statement.bindString(9, entity.getSni());
        statement.bindString(10, entity.getFingerprint());
        statement.bindString(11, entity.getPublicKey());
        statement.bindString(12, entity.getShortId());
        statement.bindString(13, entity.getRemark());
        final int _tmp = entity.isActive() ? 1 : 0;
        statement.bindLong(14, _tmp);
        statement.bindLong(15, entity.getPingMs());
        final int _tmp_1 = entity.isFree() ? 1 : 0;
        statement.bindLong(16, _tmp_1);
        statement.bindString(17, entity.getCountry());
        statement.bindLong(18, entity.getAddedAt());
        statement.bindLong(19, entity.getLastCheck());
        statement.bindString(20, entity.getHealthState());
        statement.bindLong(21, entity.getFailureCount());
        statement.bindString(22, entity.getSource());
        final int _tmp_2 = entity.isFavorite() ? 1 : 0;
        statement.bindLong(23, _tmp_2);
        statement.bindLong(24, entity.getTcpLatencyMs());
        statement.bindLong(25, entity.getTlsLatencyMs());
        statement.bindLong(26, entity.getHttpLatencyMs());
      }
    };
    this.__updateAdapterOfVlessConfigEntity = new EntityDeletionOrUpdateAdapter<VlessConfigEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `vless_configs` SET `id` = ?,`name` = ?,`address` = ?,`port` = ?,`uuid` = ?,`protocolType` = ?,`flow` = ?,`security` = ?,`sni` = ?,`fingerprint` = ?,`publicKey` = ?,`shortId` = ?,`remark` = ?,`isActive` = ?,`pingMs` = ?,`isFree` = ?,`country` = ?,`addedAt` = ?,`lastCheck` = ?,`healthState` = ?,`failureCount` = ?,`source` = ?,`isFavorite` = ?,`tcpLatencyMs` = ?,`tlsLatencyMs` = ?,`httpLatencyMs` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final VlessConfigEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getAddress());
        statement.bindLong(4, entity.getPort());
        statement.bindString(5, entity.getUuid());
        statement.bindString(6, entity.getProtocolType());
        statement.bindString(7, entity.getFlow());
        statement.bindString(8, entity.getSecurity());
        statement.bindString(9, entity.getSni());
        statement.bindString(10, entity.getFingerprint());
        statement.bindString(11, entity.getPublicKey());
        statement.bindString(12, entity.getShortId());
        statement.bindString(13, entity.getRemark());
        final int _tmp = entity.isActive() ? 1 : 0;
        statement.bindLong(14, _tmp);
        statement.bindLong(15, entity.getPingMs());
        final int _tmp_1 = entity.isFree() ? 1 : 0;
        statement.bindLong(16, _tmp_1);
        statement.bindString(17, entity.getCountry());
        statement.bindLong(18, entity.getAddedAt());
        statement.bindLong(19, entity.getLastCheck());
        statement.bindString(20, entity.getHealthState());
        statement.bindLong(21, entity.getFailureCount());
        statement.bindString(22, entity.getSource());
        final int _tmp_2 = entity.isFavorite() ? 1 : 0;
        statement.bindLong(23, _tmp_2);
        statement.bindLong(24, entity.getTcpLatencyMs());
        statement.bindLong(25, entity.getTlsLatencyMs());
        statement.bindLong(26, entity.getHttpLatencyMs());
        statement.bindString(27, entity.getId());
      }
    };
    this.__preparedStmtOfSetActive = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE vless_configs SET isActive = (id = ?)";
        return _query;
      }
    };
    this.__preparedStmtOfClearActive = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE vless_configs SET isActive = 0";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM vless_configs WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfClearFreeNodes = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM vless_configs WHERE isFree = 1";
        return _query;
      }
    };
    this.__preparedStmtOfClearAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM vless_configs";
        return _query;
      }
    };
  }

  @Override
  public Object insertOrUpdate(final VlessConfigEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfVlessConfigEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertAll(final List<VlessConfigEntity> entities,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfVlessConfigEntity.insert(entities);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final VlessConfigEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfVlessConfigEntity.handle(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object setActive(final String activeId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetActive.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, activeId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfSetActive.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearActive(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearActive.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearActive.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final String id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearFreeNodes(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearFreeNodes.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearFreeNodes.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<VlessConfigEntity>> getAllFlow() {
    final String _sql = "SELECT * FROM vless_configs ORDER BY isFavorite DESC, pingMs ASC, addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"vless_configs"}, new Callable<List<VlessConfigEntity>>() {
      @Override
      @NonNull
      public List<VlessConfigEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "uuid");
          final int _cursorIndexOfProtocolType = CursorUtil.getColumnIndexOrThrow(_cursor, "protocolType");
          final int _cursorIndexOfFlow = CursorUtil.getColumnIndexOrThrow(_cursor, "flow");
          final int _cursorIndexOfSecurity = CursorUtil.getColumnIndexOrThrow(_cursor, "security");
          final int _cursorIndexOfSni = CursorUtil.getColumnIndexOrThrow(_cursor, "sni");
          final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
          final int _cursorIndexOfPublicKey = CursorUtil.getColumnIndexOrThrow(_cursor, "publicKey");
          final int _cursorIndexOfShortId = CursorUtil.getColumnIndexOrThrow(_cursor, "shortId");
          final int _cursorIndexOfRemark = CursorUtil.getColumnIndexOrThrow(_cursor, "remark");
          final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
          final int _cursorIndexOfPingMs = CursorUtil.getColumnIndexOrThrow(_cursor, "pingMs");
          final int _cursorIndexOfIsFree = CursorUtil.getColumnIndexOrThrow(_cursor, "isFree");
          final int _cursorIndexOfCountry = CursorUtil.getColumnIndexOrThrow(_cursor, "country");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfHealthState = CursorUtil.getColumnIndexOrThrow(_cursor, "healthState");
          final int _cursorIndexOfFailureCount = CursorUtil.getColumnIndexOrThrow(_cursor, "failureCount");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfIsFavorite = CursorUtil.getColumnIndexOrThrow(_cursor, "isFavorite");
          final int _cursorIndexOfTcpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tcpLatencyMs");
          final int _cursorIndexOfTlsLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tlsLatencyMs");
          final int _cursorIndexOfHttpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "httpLatencyMs");
          final List<VlessConfigEntity> _result = new ArrayList<VlessConfigEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final VlessConfigEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUuid;
            _tmpUuid = _cursor.getString(_cursorIndexOfUuid);
            final String _tmpProtocolType;
            _tmpProtocolType = _cursor.getString(_cursorIndexOfProtocolType);
            final String _tmpFlow;
            _tmpFlow = _cursor.getString(_cursorIndexOfFlow);
            final String _tmpSecurity;
            _tmpSecurity = _cursor.getString(_cursorIndexOfSecurity);
            final String _tmpSni;
            _tmpSni = _cursor.getString(_cursorIndexOfSni);
            final String _tmpFingerprint;
            _tmpFingerprint = _cursor.getString(_cursorIndexOfFingerprint);
            final String _tmpPublicKey;
            _tmpPublicKey = _cursor.getString(_cursorIndexOfPublicKey);
            final String _tmpShortId;
            _tmpShortId = _cursor.getString(_cursorIndexOfShortId);
            final String _tmpRemark;
            _tmpRemark = _cursor.getString(_cursorIndexOfRemark);
            final boolean _tmpIsActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsActive);
            _tmpIsActive = _tmp != 0;
            final int _tmpPingMs;
            _tmpPingMs = _cursor.getInt(_cursorIndexOfPingMs);
            final boolean _tmpIsFree;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsFree);
            _tmpIsFree = _tmp_1 != 0;
            final String _tmpCountry;
            _tmpCountry = _cursor.getString(_cursorIndexOfCountry);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final String _tmpHealthState;
            _tmpHealthState = _cursor.getString(_cursorIndexOfHealthState);
            final int _tmpFailureCount;
            _tmpFailureCount = _cursor.getInt(_cursorIndexOfFailureCount);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final boolean _tmpIsFavorite;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsFavorite);
            _tmpIsFavorite = _tmp_2 != 0;
            final int _tmpTcpLatencyMs;
            _tmpTcpLatencyMs = _cursor.getInt(_cursorIndexOfTcpLatencyMs);
            final int _tmpTlsLatencyMs;
            _tmpTlsLatencyMs = _cursor.getInt(_cursorIndexOfTlsLatencyMs);
            final int _tmpHttpLatencyMs;
            _tmpHttpLatencyMs = _cursor.getInt(_cursorIndexOfHttpLatencyMs);
            _item = new VlessConfigEntity(_tmpId,_tmpName,_tmpAddress,_tmpPort,_tmpUuid,_tmpProtocolType,_tmpFlow,_tmpSecurity,_tmpSni,_tmpFingerprint,_tmpPublicKey,_tmpShortId,_tmpRemark,_tmpIsActive,_tmpPingMs,_tmpIsFree,_tmpCountry,_tmpAddedAt,_tmpLastCheck,_tmpHealthState,_tmpFailureCount,_tmpSource,_tmpIsFavorite,_tmpTcpLatencyMs,_tmpTlsLatencyMs,_tmpHttpLatencyMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getAll(final Continuation<? super List<VlessConfigEntity>> $completion) {
    final String _sql = "SELECT * FROM vless_configs ORDER BY isFavorite DESC, pingMs ASC, addedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<VlessConfigEntity>>() {
      @Override
      @NonNull
      public List<VlessConfigEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "uuid");
          final int _cursorIndexOfProtocolType = CursorUtil.getColumnIndexOrThrow(_cursor, "protocolType");
          final int _cursorIndexOfFlow = CursorUtil.getColumnIndexOrThrow(_cursor, "flow");
          final int _cursorIndexOfSecurity = CursorUtil.getColumnIndexOrThrow(_cursor, "security");
          final int _cursorIndexOfSni = CursorUtil.getColumnIndexOrThrow(_cursor, "sni");
          final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
          final int _cursorIndexOfPublicKey = CursorUtil.getColumnIndexOrThrow(_cursor, "publicKey");
          final int _cursorIndexOfShortId = CursorUtil.getColumnIndexOrThrow(_cursor, "shortId");
          final int _cursorIndexOfRemark = CursorUtil.getColumnIndexOrThrow(_cursor, "remark");
          final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
          final int _cursorIndexOfPingMs = CursorUtil.getColumnIndexOrThrow(_cursor, "pingMs");
          final int _cursorIndexOfIsFree = CursorUtil.getColumnIndexOrThrow(_cursor, "isFree");
          final int _cursorIndexOfCountry = CursorUtil.getColumnIndexOrThrow(_cursor, "country");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfHealthState = CursorUtil.getColumnIndexOrThrow(_cursor, "healthState");
          final int _cursorIndexOfFailureCount = CursorUtil.getColumnIndexOrThrow(_cursor, "failureCount");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfIsFavorite = CursorUtil.getColumnIndexOrThrow(_cursor, "isFavorite");
          final int _cursorIndexOfTcpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tcpLatencyMs");
          final int _cursorIndexOfTlsLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tlsLatencyMs");
          final int _cursorIndexOfHttpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "httpLatencyMs");
          final List<VlessConfigEntity> _result = new ArrayList<VlessConfigEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final VlessConfigEntity _item;
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUuid;
            _tmpUuid = _cursor.getString(_cursorIndexOfUuid);
            final String _tmpProtocolType;
            _tmpProtocolType = _cursor.getString(_cursorIndexOfProtocolType);
            final String _tmpFlow;
            _tmpFlow = _cursor.getString(_cursorIndexOfFlow);
            final String _tmpSecurity;
            _tmpSecurity = _cursor.getString(_cursorIndexOfSecurity);
            final String _tmpSni;
            _tmpSni = _cursor.getString(_cursorIndexOfSni);
            final String _tmpFingerprint;
            _tmpFingerprint = _cursor.getString(_cursorIndexOfFingerprint);
            final String _tmpPublicKey;
            _tmpPublicKey = _cursor.getString(_cursorIndexOfPublicKey);
            final String _tmpShortId;
            _tmpShortId = _cursor.getString(_cursorIndexOfShortId);
            final String _tmpRemark;
            _tmpRemark = _cursor.getString(_cursorIndexOfRemark);
            final boolean _tmpIsActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsActive);
            _tmpIsActive = _tmp != 0;
            final int _tmpPingMs;
            _tmpPingMs = _cursor.getInt(_cursorIndexOfPingMs);
            final boolean _tmpIsFree;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsFree);
            _tmpIsFree = _tmp_1 != 0;
            final String _tmpCountry;
            _tmpCountry = _cursor.getString(_cursorIndexOfCountry);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final String _tmpHealthState;
            _tmpHealthState = _cursor.getString(_cursorIndexOfHealthState);
            final int _tmpFailureCount;
            _tmpFailureCount = _cursor.getInt(_cursorIndexOfFailureCount);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final boolean _tmpIsFavorite;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsFavorite);
            _tmpIsFavorite = _tmp_2 != 0;
            final int _tmpTcpLatencyMs;
            _tmpTcpLatencyMs = _cursor.getInt(_cursorIndexOfTcpLatencyMs);
            final int _tmpTlsLatencyMs;
            _tmpTlsLatencyMs = _cursor.getInt(_cursorIndexOfTlsLatencyMs);
            final int _tmpHttpLatencyMs;
            _tmpHttpLatencyMs = _cursor.getInt(_cursorIndexOfHttpLatencyMs);
            _item = new VlessConfigEntity(_tmpId,_tmpName,_tmpAddress,_tmpPort,_tmpUuid,_tmpProtocolType,_tmpFlow,_tmpSecurity,_tmpSni,_tmpFingerprint,_tmpPublicKey,_tmpShortId,_tmpRemark,_tmpIsActive,_tmpPingMs,_tmpIsFree,_tmpCountry,_tmpAddedAt,_tmpLastCheck,_tmpHealthState,_tmpFailureCount,_tmpSource,_tmpIsFavorite,_tmpTcpLatencyMs,_tmpTlsLatencyMs,_tmpHttpLatencyMs);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getById(final String id,
      final Continuation<? super VlessConfigEntity> $completion) {
    final String _sql = "SELECT * FROM vless_configs WHERE id = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<VlessConfigEntity>() {
      @Override
      @Nullable
      public VlessConfigEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "uuid");
          final int _cursorIndexOfProtocolType = CursorUtil.getColumnIndexOrThrow(_cursor, "protocolType");
          final int _cursorIndexOfFlow = CursorUtil.getColumnIndexOrThrow(_cursor, "flow");
          final int _cursorIndexOfSecurity = CursorUtil.getColumnIndexOrThrow(_cursor, "security");
          final int _cursorIndexOfSni = CursorUtil.getColumnIndexOrThrow(_cursor, "sni");
          final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
          final int _cursorIndexOfPublicKey = CursorUtil.getColumnIndexOrThrow(_cursor, "publicKey");
          final int _cursorIndexOfShortId = CursorUtil.getColumnIndexOrThrow(_cursor, "shortId");
          final int _cursorIndexOfRemark = CursorUtil.getColumnIndexOrThrow(_cursor, "remark");
          final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
          final int _cursorIndexOfPingMs = CursorUtil.getColumnIndexOrThrow(_cursor, "pingMs");
          final int _cursorIndexOfIsFree = CursorUtil.getColumnIndexOrThrow(_cursor, "isFree");
          final int _cursorIndexOfCountry = CursorUtil.getColumnIndexOrThrow(_cursor, "country");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfHealthState = CursorUtil.getColumnIndexOrThrow(_cursor, "healthState");
          final int _cursorIndexOfFailureCount = CursorUtil.getColumnIndexOrThrow(_cursor, "failureCount");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfIsFavorite = CursorUtil.getColumnIndexOrThrow(_cursor, "isFavorite");
          final int _cursorIndexOfTcpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tcpLatencyMs");
          final int _cursorIndexOfTlsLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tlsLatencyMs");
          final int _cursorIndexOfHttpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "httpLatencyMs");
          final VlessConfigEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUuid;
            _tmpUuid = _cursor.getString(_cursorIndexOfUuid);
            final String _tmpProtocolType;
            _tmpProtocolType = _cursor.getString(_cursorIndexOfProtocolType);
            final String _tmpFlow;
            _tmpFlow = _cursor.getString(_cursorIndexOfFlow);
            final String _tmpSecurity;
            _tmpSecurity = _cursor.getString(_cursorIndexOfSecurity);
            final String _tmpSni;
            _tmpSni = _cursor.getString(_cursorIndexOfSni);
            final String _tmpFingerprint;
            _tmpFingerprint = _cursor.getString(_cursorIndexOfFingerprint);
            final String _tmpPublicKey;
            _tmpPublicKey = _cursor.getString(_cursorIndexOfPublicKey);
            final String _tmpShortId;
            _tmpShortId = _cursor.getString(_cursorIndexOfShortId);
            final String _tmpRemark;
            _tmpRemark = _cursor.getString(_cursorIndexOfRemark);
            final boolean _tmpIsActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsActive);
            _tmpIsActive = _tmp != 0;
            final int _tmpPingMs;
            _tmpPingMs = _cursor.getInt(_cursorIndexOfPingMs);
            final boolean _tmpIsFree;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsFree);
            _tmpIsFree = _tmp_1 != 0;
            final String _tmpCountry;
            _tmpCountry = _cursor.getString(_cursorIndexOfCountry);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final String _tmpHealthState;
            _tmpHealthState = _cursor.getString(_cursorIndexOfHealthState);
            final int _tmpFailureCount;
            _tmpFailureCount = _cursor.getInt(_cursorIndexOfFailureCount);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final boolean _tmpIsFavorite;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsFavorite);
            _tmpIsFavorite = _tmp_2 != 0;
            final int _tmpTcpLatencyMs;
            _tmpTcpLatencyMs = _cursor.getInt(_cursorIndexOfTcpLatencyMs);
            final int _tmpTlsLatencyMs;
            _tmpTlsLatencyMs = _cursor.getInt(_cursorIndexOfTlsLatencyMs);
            final int _tmpHttpLatencyMs;
            _tmpHttpLatencyMs = _cursor.getInt(_cursorIndexOfHttpLatencyMs);
            _result = new VlessConfigEntity(_tmpId,_tmpName,_tmpAddress,_tmpPort,_tmpUuid,_tmpProtocolType,_tmpFlow,_tmpSecurity,_tmpSni,_tmpFingerprint,_tmpPublicKey,_tmpShortId,_tmpRemark,_tmpIsActive,_tmpPingMs,_tmpIsFree,_tmpCountry,_tmpAddedAt,_tmpLastCheck,_tmpHealthState,_tmpFailureCount,_tmpSource,_tmpIsFavorite,_tmpTcpLatencyMs,_tmpTlsLatencyMs,_tmpHttpLatencyMs);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getActiveConfig(final Continuation<? super VlessConfigEntity> $completion) {
    final String _sql = "SELECT * FROM vless_configs WHERE isActive = 1 LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<VlessConfigEntity>() {
      @Override
      @Nullable
      public VlessConfigEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfAddress = CursorUtil.getColumnIndexOrThrow(_cursor, "address");
          final int _cursorIndexOfPort = CursorUtil.getColumnIndexOrThrow(_cursor, "port");
          final int _cursorIndexOfUuid = CursorUtil.getColumnIndexOrThrow(_cursor, "uuid");
          final int _cursorIndexOfProtocolType = CursorUtil.getColumnIndexOrThrow(_cursor, "protocolType");
          final int _cursorIndexOfFlow = CursorUtil.getColumnIndexOrThrow(_cursor, "flow");
          final int _cursorIndexOfSecurity = CursorUtil.getColumnIndexOrThrow(_cursor, "security");
          final int _cursorIndexOfSni = CursorUtil.getColumnIndexOrThrow(_cursor, "sni");
          final int _cursorIndexOfFingerprint = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerprint");
          final int _cursorIndexOfPublicKey = CursorUtil.getColumnIndexOrThrow(_cursor, "publicKey");
          final int _cursorIndexOfShortId = CursorUtil.getColumnIndexOrThrow(_cursor, "shortId");
          final int _cursorIndexOfRemark = CursorUtil.getColumnIndexOrThrow(_cursor, "remark");
          final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
          final int _cursorIndexOfPingMs = CursorUtil.getColumnIndexOrThrow(_cursor, "pingMs");
          final int _cursorIndexOfIsFree = CursorUtil.getColumnIndexOrThrow(_cursor, "isFree");
          final int _cursorIndexOfCountry = CursorUtil.getColumnIndexOrThrow(_cursor, "country");
          final int _cursorIndexOfAddedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "addedAt");
          final int _cursorIndexOfLastCheck = CursorUtil.getColumnIndexOrThrow(_cursor, "lastCheck");
          final int _cursorIndexOfHealthState = CursorUtil.getColumnIndexOrThrow(_cursor, "healthState");
          final int _cursorIndexOfFailureCount = CursorUtil.getColumnIndexOrThrow(_cursor, "failureCount");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final int _cursorIndexOfIsFavorite = CursorUtil.getColumnIndexOrThrow(_cursor, "isFavorite");
          final int _cursorIndexOfTcpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tcpLatencyMs");
          final int _cursorIndexOfTlsLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "tlsLatencyMs");
          final int _cursorIndexOfHttpLatencyMs = CursorUtil.getColumnIndexOrThrow(_cursor, "httpLatencyMs");
          final VlessConfigEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            _tmpId = _cursor.getString(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpAddress;
            _tmpAddress = _cursor.getString(_cursorIndexOfAddress);
            final int _tmpPort;
            _tmpPort = _cursor.getInt(_cursorIndexOfPort);
            final String _tmpUuid;
            _tmpUuid = _cursor.getString(_cursorIndexOfUuid);
            final String _tmpProtocolType;
            _tmpProtocolType = _cursor.getString(_cursorIndexOfProtocolType);
            final String _tmpFlow;
            _tmpFlow = _cursor.getString(_cursorIndexOfFlow);
            final String _tmpSecurity;
            _tmpSecurity = _cursor.getString(_cursorIndexOfSecurity);
            final String _tmpSni;
            _tmpSni = _cursor.getString(_cursorIndexOfSni);
            final String _tmpFingerprint;
            _tmpFingerprint = _cursor.getString(_cursorIndexOfFingerprint);
            final String _tmpPublicKey;
            _tmpPublicKey = _cursor.getString(_cursorIndexOfPublicKey);
            final String _tmpShortId;
            _tmpShortId = _cursor.getString(_cursorIndexOfShortId);
            final String _tmpRemark;
            _tmpRemark = _cursor.getString(_cursorIndexOfRemark);
            final boolean _tmpIsActive;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsActive);
            _tmpIsActive = _tmp != 0;
            final int _tmpPingMs;
            _tmpPingMs = _cursor.getInt(_cursorIndexOfPingMs);
            final boolean _tmpIsFree;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsFree);
            _tmpIsFree = _tmp_1 != 0;
            final String _tmpCountry;
            _tmpCountry = _cursor.getString(_cursorIndexOfCountry);
            final long _tmpAddedAt;
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt);
            final long _tmpLastCheck;
            _tmpLastCheck = _cursor.getLong(_cursorIndexOfLastCheck);
            final String _tmpHealthState;
            _tmpHealthState = _cursor.getString(_cursorIndexOfHealthState);
            final int _tmpFailureCount;
            _tmpFailureCount = _cursor.getInt(_cursorIndexOfFailureCount);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            final boolean _tmpIsFavorite;
            final int _tmp_2;
            _tmp_2 = _cursor.getInt(_cursorIndexOfIsFavorite);
            _tmpIsFavorite = _tmp_2 != 0;
            final int _tmpTcpLatencyMs;
            _tmpTcpLatencyMs = _cursor.getInt(_cursorIndexOfTcpLatencyMs);
            final int _tmpTlsLatencyMs;
            _tmpTlsLatencyMs = _cursor.getInt(_cursorIndexOfTlsLatencyMs);
            final int _tmpHttpLatencyMs;
            _tmpHttpLatencyMs = _cursor.getInt(_cursorIndexOfHttpLatencyMs);
            _result = new VlessConfigEntity(_tmpId,_tmpName,_tmpAddress,_tmpPort,_tmpUuid,_tmpProtocolType,_tmpFlow,_tmpSecurity,_tmpSni,_tmpFingerprint,_tmpPublicKey,_tmpShortId,_tmpRemark,_tmpIsActive,_tmpPingMs,_tmpIsFree,_tmpCountry,_tmpAddedAt,_tmpLastCheck,_tmpHealthState,_tmpFailureCount,_tmpSource,_tmpIsFavorite,_tmpTcpLatencyMs,_tmpTlsLatencyMs,_tmpHttpLatencyMs);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
