const fs = require("node:fs");
const path = require("node:path");

function openDatabase(filename) {
  if (filename !== ":memory:") {
    fs.mkdirSync(path.dirname(filename), { recursive: true });
  }

  const db = openSqlite(filename);

  db.exec(`
    CREATE TABLE IF NOT EXISTS batteries (
      bms_uid TEXT PRIMARY KEY,
      bluetooth_name TEXT,
      bluetooth_address TEXT,
      advertised_name TEXT,
      hardware_family TEXT,
      owner_name TEXT,
      owner_phone TEXT,
      owner_email TEXT,
      first_seen_at INTEGER NOT NULL,
      last_seen_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS telemetry (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bms_uid TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      voltage REAL,
      current REAL,
      soc REAL,
      remaining_ah REAL,
      estimated_full_ah REAL,
      nominal_capacity_ah REAL,
      capacity_source TEXT,
      cell_diff_v REAL,
      min_cell_v REAL,
      max_cell_v REAL,
      min_temp INTEGER,
      max_temp INTEGER,
      charge_mos INTEGER,
      discharge_mos INTEGER,
      cells_json TEXT NOT NULL,
      temps_json TEXT NOT NULL,
      errors_json TEXT NOT NULL,
      raw_json TEXT NOT NULL,
      events_json TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      FOREIGN KEY (bms_uid) REFERENCES batteries(bms_uid) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS telemetry_bms_time_idx
      ON telemetry(bms_uid, received_at DESC);

    CREATE TABLE IF NOT EXISTS config_checks (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bms_uid TEXT NOT NULL,
      received_at INTEGER NOT NULL,
      checked_at INTEGER NOT NULL,
      template_id TEXT NOT NULL,
      template_version INTEGER NOT NULL,
      status TEXT NOT NULL,
      series_count INTEGER,
      mismatch_count INTEGER NOT NULL,
      missing_count INTEGER NOT NULL,
      mismatches_json TEXT NOT NULL,
      missing_json TEXT NOT NULL,
      unverified_json TEXT NOT NULL,
      config_snapshot_json TEXT,
      payload_json TEXT NOT NULL,
      FOREIGN KEY (bms_uid) REFERENCES batteries(bms_uid) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS config_checks_bms_time_idx
      ON config_checks(bms_uid, checked_at DESC);

    CREATE TABLE IF NOT EXISTS write_commands (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bms_uid TEXT NOT NULL,
      param_key TEXT NOT NULL,
      label TEXT,
      register TEXT NOT NULL,
      unit TEXT,
      value REAL NOT NULL,
      raw_value INTEGER NOT NULL,
      scale REAL NOT NULL,
      offset REAL NOT NULL,
      status TEXT NOT NULL,
      error TEXT,
      actual REAL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (bms_uid) REFERENCES batteries(bms_uid) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS write_commands_bms_status_idx
      ON write_commands(bms_uid, status, id);
  `);

  ensureColumn(db, "batteries", "advertised_name", "TEXT");
  ensureColumn(db, "batteries", "hardware_family", "TEXT");
  ensureColumn(db, "batteries", "owner_name", "TEXT");
  ensureColumn(db, "batteries", "owner_phone", "TEXT");
  ensureColumn(db, "batteries", "owner_email", "TEXT");

  const upsertBattery = db.prepare(`
    INSERT INTO batteries (
      bms_uid, bluetooth_name, bluetooth_address, advertised_name, hardware_family,
      owner_name, owner_phone, owner_email, first_seen_at, last_seen_at
    ) VALUES (
      @bms_uid, @bluetooth_name, @bluetooth_address, @advertised_name, @hardware_family,
      @owner_name, @owner_phone, @owner_email, @received_at, @received_at
    )
    ON CONFLICT(bms_uid) DO UPDATE SET
      bluetooth_name = COALESCE(excluded.bluetooth_name, batteries.bluetooth_name),
      bluetooth_address = COALESCE(excluded.bluetooth_address, batteries.bluetooth_address),
      advertised_name = COALESCE(excluded.advertised_name, batteries.advertised_name),
      hardware_family = CASE
        WHEN excluded.hardware_family = 'dl_red' THEN 'dl_red'
        WHEN batteries.hardware_family IS NOT NULL THEN batteries.hardware_family
        ELSE excluded.hardware_family
      END,
      owner_name = COALESCE(excluded.owner_name, batteries.owner_name),
      owner_phone = COALESCE(excluded.owner_phone, batteries.owner_phone),
      owner_email = COALESCE(excluded.owner_email, batteries.owner_email),
      last_seen_at = excluded.last_seen_at
  `);

  const insertTelemetryRow = db.prepare(`
    INSERT INTO telemetry (
      bms_uid, received_at, voltage, current, soc, remaining_ah,
      estimated_full_ah, nominal_capacity_ah, capacity_source, cell_diff_v,
      min_cell_v, max_cell_v, min_temp, max_temp, charge_mos, discharge_mos,
      cells_json, temps_json, errors_json, raw_json, events_json, payload_json
    ) VALUES (
      @bms_uid, @received_at, @voltage, @current, @soc, @remaining_ah,
      @estimated_full_ah, @nominal_capacity_ah, @capacity_source, @cell_diff_v,
      @min_cell_v, @max_cell_v, @min_temp, @max_temp, @charge_mos, @discharge_mos,
      @cells_json, @temps_json, @errors_json, @raw_json, @events_json, @payload_json
    )
  `);

  const saveTelemetry = db.transaction((payload, receivedAt) => {
    const row = {
      ...batteryIdentity(payload),
      received_at: receivedAt,
      voltage: payload.voltage ?? null,
      current: payload.current ?? null,
      soc: payload.soc ?? null,
      remaining_ah: payload.remaining_ah ?? null,
      estimated_full_ah: payload.estimated_full_ah ?? null,
      nominal_capacity_ah: payload.nominal_capacity_ah ?? null,
      capacity_source: payload.capacity_source ?? null,
      cell_diff_v: payload.cell_diff_v ?? null,
      min_cell_v: payload.min_cell_v ?? null,
      max_cell_v: payload.max_cell_v ?? null,
      min_temp: payload.min_temp ?? null,
      max_temp: payload.max_temp ?? null,
      charge_mos: booleanToInteger(payload.charge_mos),
      discharge_mos: booleanToInteger(payload.discharge_mos),
      cells_json: JSON.stringify(payload.cells ?? {}),
      temps_json: JSON.stringify(payload.temps ?? {}),
      errors_json: JSON.stringify(payload.errors ?? []),
      raw_json: JSON.stringify(payload.raw ?? {}),
      events_json: JSON.stringify(payload.events ?? []),
      payload_json: JSON.stringify(payload),
    };

    upsertBattery.run(row);
    return Number(insertTelemetryRow.run(row).lastInsertRowid);
  });

  const insertConfigCheckRow = db.prepare(`
    INSERT INTO config_checks (
      bms_uid, received_at, checked_at, template_id, template_version, status,
      series_count, mismatch_count, missing_count, mismatches_json, missing_json,
      unverified_json, config_snapshot_json, payload_json
    ) VALUES (
      @bms_uid, @received_at, @checked_at, @template_id, @template_version, @status,
      @series_count, @mismatch_count, @missing_count, @mismatches_json, @missing_json,
      @unverified_json, @config_snapshot_json, @payload_json
    )
  `);

  const saveConfigCheck = db.transaction((payload, receivedAt) => {
    const check = payload.template_check;
    const battery = {
      ...batteryIdentity(payload),
      received_at: receivedAt,
    };
    const row = {
      ...battery,
      checked_at: check.checked_at,
      template_id: check.template_id,
      template_version: check.template_version,
      status: check.status,
      series_count: check.series_count ?? null,
      mismatch_count: check.mismatch_count,
      missing_count: check.missing_count,
      mismatches_json: JSON.stringify(check.mismatches),
      missing_json: JSON.stringify(check.missing),
      unverified_json: JSON.stringify(check.unverified),
      config_snapshot_json: payload.config == null ? null : JSON.stringify(payload.config),
      payload_json: JSON.stringify(payload),
    };

    upsertBattery.run(battery);
    return Number(insertConfigCheckRow.run(row).lastInsertRowid);
  });

  return {
    close: () => db.close(),

    insertTelemetry(payload, receivedAt = Date.now()) {
      return saveTelemetry(payload, receivedAt);
    },

    insertConfigCheck(payload, receivedAt = Date.now()) {
      return saveConfigCheck(payload, receivedAt);
    },

    enqueueWriteCommand(command, receivedAt = Date.now()) {
      db.prepare(`
        UPDATE write_commands
        SET status = 'superseded', updated_at = @updated_at
        WHERE bms_uid = @bms_uid
          AND param_key = @param_key
          AND status IN ('pending', 'writing')
      `).run({
        bms_uid: command.bms_uid,
        param_key: command.param_key,
        updated_at: receivedAt,
      });

      const result = db.prepare(`
        INSERT INTO write_commands (
          bms_uid, param_key, label, register, unit, value, raw_value,
          scale, offset, status, error, actual, created_at, updated_at
        ) VALUES (
          @bms_uid, @param_key, @label, @register, @unit, @value, @raw_value,
          @scale, @offset, 'pending', NULL, NULL, @created_at, @updated_at
        )
      `).run({
        bms_uid: command.bms_uid,
        param_key: command.param_key,
        label: command.label ?? null,
        register: command.register,
        unit: command.unit ?? null,
        value: command.value,
        raw_value: command.raw_value,
        scale: command.scale,
        offset: command.offset,
        created_at: receivedAt,
        updated_at: receivedAt,
      });
      return Number(result.lastInsertRowid);
    },

    listWriteCommands(bmsUid, options = {}) {
      const limit = Number.isInteger(options.limit) ? options.limit : 20;
      const staleBefore = options.staleBefore ?? 0;
      const status = options.status;
      const rows = status === "pending"
        ? db.prepare(`
            SELECT *
            FROM write_commands
            WHERE bms_uid = ?
              AND (
                status = 'pending'
                OR (status = 'writing' AND updated_at < ?)
              )
            ORDER BY id ASC
            LIMIT ?
          `).all(bmsUid, staleBefore, limit)
        : db.prepare(`
            SELECT *
            FROM write_commands
            WHERE bms_uid = ?
            ORDER BY id DESC
            LIMIT ?
          `).all(bmsUid, limit);
      return rows.map(mapWriteCommand);
    },

    getWriteCommand(id) {
      const row = db.prepare("SELECT * FROM write_commands WHERE id = ?").get(id);
      return row ? mapWriteCommand(row) : null;
    },

    claimWriteCommand(id, updatedAt = Date.now()) {
      const result = db.prepare(`
        UPDATE write_commands
        SET status = 'writing', updated_at = ?
        WHERE id = ? AND status IN ('pending', 'writing')
      `).run(updatedAt, id);
      return Number(result.changes) > 0;
    },

    updateWriteCommand(id, patch, updatedAt = Date.now()) {
      const current = db.prepare("SELECT * FROM write_commands WHERE id = ?").get(id);
      if (!current) return null;
      db.prepare(`
        UPDATE write_commands
        SET status = @status,
            error = @error,
            actual = @actual,
            updated_at = @updated_at
        WHERE id = @id
      `).run({
        id,
        status: patch.status ?? current.status,
        error: patch.error === undefined ? current.error : patch.error,
        actual: patch.actual === undefined ? current.actual : patch.actual,
        updated_at: updatedAt,
      });
      const row = db.prepare("SELECT * FROM write_commands WHERE id = ?").get(id);
      return row ? mapWriteCommand(row) : null;
    },

    hasBattery(bmsUid) {
      return db.prepare("SELECT 1 FROM batteries WHERE bms_uid = ?").get(bmsUid) != null;
    },

    getBattery(bmsUid) {
      return db.prepare(`
        SELECT bms_uid, bluetooth_name, advertised_name, hardware_family
        FROM batteries
        WHERE bms_uid = ?
      `).get(bmsUid) || null;
    },

    listBatteries() {
      const rows = db.prepare(`
        SELECT
          b.bms_uid,
          b.bluetooth_name,
          b.bluetooth_address,
          b.advertised_name,
          b.hardware_family,
          b.owner_name,
          b.owner_phone,
          b.owner_email,
          b.first_seen_at,
          b.last_seen_at,
          t.id AS telemetry_id,
          t.received_at,
          t.voltage,
          t.current,
          t.soc,
          t.remaining_ah,
          t.estimated_full_ah,
          t.nominal_capacity_ah,
          t.cell_diff_v,
          t.min_cell_v,
          t.max_cell_v,
          t.min_temp,
          t.max_temp,
          t.charge_mos,
          t.discharge_mos,
          t.cells_json,
          t.temps_json,
          t.errors_json,
          c.id AS config_check_id,
          c.received_at AS config_received_at,
          c.checked_at AS config_checked_at,
          c.template_id,
          c.template_version,
          c.status AS config_status,
          c.series_count,
          c.mismatch_count,
          c.missing_count,
          c.mismatches_json,
          c.missing_json,
          c.unverified_json
        FROM batteries b
        LEFT JOIN telemetry t ON t.id = (
          SELECT latest.id
          FROM telemetry latest
          WHERE latest.bms_uid = b.bms_uid
          ORDER BY latest.received_at DESC, latest.id DESC
          LIMIT 1
        )
        LEFT JOIN config_checks c ON c.id = (
          SELECT latest_check.id
          FROM config_checks latest_check
          WHERE latest_check.bms_uid = b.bms_uid
          ORDER BY latest_check.checked_at DESC, latest_check.id DESC
          LIMIT 1
        )
        ORDER BY b.last_seen_at DESC
      `).all();

      return rows.map((row) => {
        const {
          errors_json: errorsJson,
          cells_json: cellsJson,
          temps_json: tempsJson,
          charge_mos: chargeMos,
          discharge_mos: dischargeMos,
          config_check_id: configCheckId,
          config_received_at: configReceivedAt,
          config_checked_at: configCheckedAt,
          template_id: templateId,
          template_version: templateVersion,
          config_status: configStatus,
          series_count: seriesCount,
          mismatch_count: mismatchCount,
          missing_count: missingCount,
          mismatches_json: mismatchesJson,
          missing_json: missingJson,
          unverified_json: unverifiedJson,
          ...battery
        } = row;
        return {
          ...battery,
          charge_mos: integerToBoolean(chargeMos),
          discharge_mos: integerToBoolean(dischargeMos),
          cells: parseJson(cellsJson, {}),
          temps: parseJson(tempsJson, {}),
          errors: parseJson(errorsJson, []),
          config_check: configCheckId == null ? null : {
            id: configCheckId,
            received_at: configReceivedAt,
            checked_at: configCheckedAt,
            template_id: templateId,
            template_version: templateVersion,
            status: configStatus,
            series_count: seriesCount,
            mismatch_count: mismatchCount,
            missing_count: missingCount,
            mismatches: parseJson(mismatchesJson, []),
            missing: parseJson(missingJson, []),
            unverified: parseJson(unverifiedJson, []),
          },
        };
      });
    },

    listTelemetry(bmsUid, limit) {
      return db.prepare(`
        SELECT id, received_at, payload_json
        FROM telemetry
        WHERE bms_uid = ?
        ORDER BY received_at DESC, id DESC
        LIMIT ?
      `).all(bmsUid, limit).map((row) => ({
        ...parseJson(row.payload_json, {}),
        id: row.id,
        received_at: row.received_at,
      }));
    },

    listConfigChecks(bmsUid, limit) {
      return db.prepare(`
        SELECT
          id, received_at, checked_at, template_id, template_version, status,
          series_count, mismatch_count, missing_count, mismatches_json,
          missing_json, unverified_json
        FROM config_checks
        WHERE bms_uid = ?
        ORDER BY checked_at DESC, id DESC
        LIMIT ?
      `).all(bmsUid, limit).map((row) => ({
        id: row.id,
        received_at: row.received_at,
        checked_at: row.checked_at,
        template_id: row.template_id,
        template_version: row.template_version,
        status: row.status,
        series_count: row.series_count,
        mismatch_count: row.mismatch_count,
        missing_count: row.missing_count,
        mismatches: parseJson(row.mismatches_json, []),
        missing: parseJson(row.missing_json, []),
        unverified: parseJson(row.unverified_json, []),
      }));
    },
  };
}

function openSqlite(filename) {
  try {
    const Database = require("better-sqlite3");
    const db = new Database(filename);
    db.pragma("journal_mode = WAL");
    db.pragma("foreign_keys = ON");
    return db;
  } catch (_error) {
    const { DatabaseSync } = require("node:sqlite");
    const db = new DatabaseSync(filename);
    db.exec("PRAGMA journal_mode = WAL;");
    db.exec("PRAGMA foreign_keys = ON;");
    return wrapNodeSqlite(db);
  }
}

function wrapNodeSqlite(db) {
  return {
    exec: (sql) => db.exec(sql),
    close: () => db.close(),
    prepare(sql) {
      const stmt = db.prepare(sql);
      return {
        run(...args) {
          const result = stmt.run(...normalizeSqliteArgs(args));
          return {
            lastInsertRowid: result.lastInsertRowid,
            changes: result.changes,
          };
        },
        get(...args) {
          return stmt.get(...normalizeSqliteArgs(args));
        },
        all(...args) {
          return stmt.all(...normalizeSqliteArgs(args));
        },
      };
    },
    transaction(fn) {
      return (...args) => {
        db.exec("BEGIN");
        try {
          const result = fn(...args);
          db.exec("COMMIT");
          return result;
        } catch (error) {
          try {
            db.exec("ROLLBACK");
          } catch (_rollbackError) {
            // Ignore rollback errors after a failed transaction.
          }
          throw error;
        }
      };
    },
  };
}

function normalizeSqliteArgs(args) {
  if (
    args.length === 1 &&
    args[0] &&
    typeof args[0] === "object" &&
    !Array.isArray(args[0])
  ) {
    const named = {};
    for (const [key, value] of Object.entries(args[0])) {
      named[
        key.startsWith("@") || key.startsWith("$") || key.startsWith(":")
          ? key
          : `@${key}`
      ] = value;
    }
    return [named];
  }
  return args;
}

function booleanToInteger(value) {
  if (value === true) return 1;
  if (value === false) return 0;
  return null;
}

function integerToBoolean(value) {
  if (value === 1) return true;
  if (value === 0) return false;
  return null;
}

function parseJson(value, fallback) {
  if (value == null) return fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function mapWriteCommand(row) {
  return {
    id: row.id,
    bms_uid: row.bms_uid,
    key: row.param_key,
    label: row.label,
    register: row.register,
    unit: row.unit,
    value: row.value,
    raw_value: row.raw_value,
    scale: row.scale,
    offset: row.offset,
    status: row.status,
    error: row.error,
    actual: row.actual,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

function ensureColumn(db, table, column, definition) {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all();
  if (columns.some((row) => row.name === column)) return;
  db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
}

function emptyToNull(value) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function looksLikeRedDlName(value) {
  return typeof value === "string" && /^\s*DL/i.test(value);
}

function inferHardwareFamily(payload) {
  const checkFamily = isPlainObject(payload?.template_check)
    ? payload.template_check.hardware_family
    : null;
  if (payload?.hardware_family === "dl_red" || checkFamily === "dl_red") return "dl_red";
  if (looksLikeRedDlName(payload?.advertised_name) || looksLikeRedDlName(payload?.bluetooth_name)) {
    return "dl_red";
  }
  if (payload?.hardware_family === "standard" || checkFamily === "standard") return "standard";
  return null;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function batteryIdentity(payload) {
  return {
    bms_uid: payload.bms_uid,
    bluetooth_name: payload.bluetooth_name ?? null,
    bluetooth_address: payload.bluetooth_address ?? null,
    advertised_name: emptyToNull(payload.advertised_name),
    hardware_family: inferHardwareFamily(payload),
    owner_name: emptyToNull(payload.owner_name),
    owner_phone: emptyToNull(payload.owner_phone),
    owner_email: emptyToNull(payload.owner_email),
  };
}

module.exports = { openDatabase };
