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

    CREATE TABLE IF NOT EXISTS service_reports (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      bms_uid TEXT NOT NULL,
      bms_sn TEXT,
      bluetooth_id TEXT,
      assembler_name TEXT NOT NULL,
      template_id TEXT NOT NULL,
      capacity_ah REAL,
      written_at INTEGER NOT NULL,
      status TEXT NOT NULL,
      items_json TEXT NOT NULL,
      payload_json TEXT NOT NULL,
      FOREIGN KEY (bms_uid) REFERENCES batteries(bms_uid) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS service_reports_bms_time_idx
      ON service_reports(bms_uid, written_at DESC);
  `);

  ensureColumn(db, "batteries", "advertised_name", "TEXT");
  ensureColumn(db, "batteries", "hardware_family", "TEXT");
  ensureColumn(db, "batteries", "owner_name", "TEXT");
  ensureColumn(db, "batteries", "owner_phone", "TEXT");
  ensureColumn(db, "batteries", "owner_email", "TEXT");
  ensureColumn(db, "batteries", "last_source", "TEXT");
  ensureColumn(db, "batteries", "assembler_name", "TEXT");
  ensureColumn(db, "batteries", "bms_sn", "TEXT");
  ensureColumn(db, "batteries", "bluetooth_id", "TEXT");

  repairBatteryIdentities(db);
  db.exec(`
    UPDATE batteries SET hardware_family = 'standard' WHERE hardware_family = 'dl_red';
    UPDATE batteries SET bms_sn = NULL WHERE bms_sn LIKE 'DL-%';
  `);

  const upsertBattery = db.prepare(`
    INSERT INTO batteries (
      bms_uid, bluetooth_name, bluetooth_address, advertised_name, hardware_family,
      owner_name, owner_phone, owner_email, last_source, assembler_name, bms_sn,
      bluetooth_id, first_seen_at, last_seen_at
    ) VALUES (
      @bms_uid, @bluetooth_name, @bluetooth_address, @advertised_name, @hardware_family,
      @owner_name, @owner_phone, @owner_email, @last_source, @assembler_name, @bms_sn,
      @bluetooth_id, @received_at, @received_at
    )
    ON CONFLICT(bms_uid) DO UPDATE SET
      bluetooth_name = COALESCE(excluded.bluetooth_name, batteries.bluetooth_name),
      bluetooth_address = COALESCE(excluded.bluetooth_address, batteries.bluetooth_address),
      advertised_name = COALESCE(excluded.advertised_name, batteries.advertised_name),
      hardware_family = COALESCE(excluded.hardware_family, batteries.hardware_family),
      owner_name = COALESCE(excluded.owner_name, batteries.owner_name),
      owner_phone = COALESCE(excluded.owner_phone, batteries.owner_phone),
      owner_email = COALESCE(excluded.owner_email, batteries.owner_email),
      last_source = COALESCE(excluded.last_source, batteries.last_source),
      assembler_name = COALESCE(excluded.assembler_name, batteries.assembler_name),
      bms_sn = COALESCE(excluded.bms_sn, batteries.bms_sn),
      bluetooth_id = COALESCE(excluded.bluetooth_id, batteries.bluetooth_id),
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

  const insertServiceReportRow = db.prepare(`
    INSERT INTO service_reports (
      bms_uid, bms_sn, bluetooth_id, assembler_name, template_id, capacity_ah,
      written_at, status, items_json, payload_json
    ) VALUES (
      @bms_uid, @bms_sn, @bluetooth_id, @assembler_name, @template_id, @capacity_ah,
      @written_at, @status, @items_json, @payload_json
    )
  `);

  const saveServiceReport = db.transaction((payload, receivedAt) => {
    const writtenAt = isSafeTimestamp(payload.written_at) ? payload.written_at : receivedAt;
    const battery = {
      ...batteryIdentity(payload),
      received_at: receivedAt,
      last_source: "service",
    };
    upsertBattery.run(battery);
    const row = {
      bms_uid: payload.bms_uid,
      bms_sn: emptyToNull(payload.bms_sn),
      bluetooth_id: emptyToNull(payload.bluetooth_id) ?? emptyToNull(payload.bluetooth_address),
      assembler_name: payload.assembler_name.trim(),
      template_id: payload.template_id.trim(),
      capacity_ah: payload.capacity_ah ?? null,
      written_at: writtenAt,
      status: payload.status,
      items_json: JSON.stringify(payload.items ?? []),
      payload_json: JSON.stringify(payload),
    };
    return Number(insertServiceReportRow.run(row).lastInsertRowid);
  });

  return {
    close: () => db.close(),

    insertTelemetry(payload, receivedAt = Date.now()) {
      return saveTelemetry(normalizePayload(payload), receivedAt);
    },

    insertConfigCheck(payload, receivedAt = Date.now()) {
      return saveConfigCheck(normalizePayload(payload), receivedAt);
    },

    insertServiceReport(payload, receivedAt = Date.now()) {
      return saveServiceReport(normalizePayload(payload), receivedAt);
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
          b.last_source,
          b.assembler_name,
          b.bms_sn,
          b.bluetooth_id,
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
          c.unverified_json,
          s.id AS service_report_id,
          s.written_at AS service_written_at,
          s.assembler_name AS service_assembler_name,
          s.template_id AS service_template_id,
          s.capacity_ah AS service_capacity_ah,
          s.status AS service_status,
          s.bms_sn AS service_bms_sn,
          s.bluetooth_id AS service_bluetooth_id
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
        LEFT JOIN service_reports s ON s.id = (
          SELECT latest_service.id
          FROM service_reports latest_service
          WHERE latest_service.bms_uid = b.bms_uid
          ORDER BY latest_service.written_at DESC, latest_service.id DESC
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
          service_report_id: serviceReportId,
          service_written_at: serviceWrittenAt,
          service_assembler_name: serviceAssemblerName,
          service_template_id: serviceTemplateId,
          service_capacity_ah: serviceCapacityAh,
          service_status: serviceStatus,
          service_bms_sn: serviceBmsSn,
          service_bluetooth_id: serviceBluetoothId,
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
          service_report: serviceReportId == null ? null : {
            id: serviceReportId,
            written_at: serviceWrittenAt,
            assembler_name: serviceAssemblerName,
            template_id: serviceTemplateId,
            capacity_ah: serviceCapacityAh,
            status: serviceStatus,
            bms_sn: serviceBmsSn,
            bluetooth_id: serviceBluetoothId,
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

    listServiceReports(bmsUid, limit) {
      return db.prepare(`
        SELECT
          id, bms_uid, bms_sn, bluetooth_id, assembler_name, template_id,
          capacity_ah, written_at, status, items_json
        FROM service_reports
        WHERE bms_uid = ?
        ORDER BY written_at DESC, id DESC
        LIMIT ?
      `).all(bmsUid, limit).map((row) => ({
        id: row.id,
        bms_uid: row.bms_uid,
        bms_sn: row.bms_sn,
        bluetooth_id: row.bluetooth_id,
        assembler_name: row.assembler_name,
        template_id: row.template_id,
        capacity_ah: row.capacity_ah,
        written_at: row.written_at,
        status: row.status,
        items: parseJson(row.items_json, []),
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

function sanitizeIdentity(value) {
  if (typeof value !== "string") return null;
  const cut = value.indexOf("\0");
  const text = (cut >= 0 ? value.slice(0, cut) : value)
    .replace(/[\u0000-\u001F\u007F]/g, "")
    .trim();
  return text.length > 0 ? text : null;
}

function normalizePayload(payload) {
  if (!isPlainObject(payload)) return payload;
  const next = { ...payload };
  const uid = sanitizeIdentity(payload.bms_uid);
  if (uid) next.bms_uid = uid;
  for (const field of [
    "bluetooth_name",
    "advertised_name",
    "bms_sn",
    "bluetooth_id",
    "bluetooth_address",
    "assembler_name",
  ]) {
    if (typeof next[field] === "string") {
      next[field] = sanitizeIdentity(next[field]) ?? "";
    }
  }
  if (typeof next.bms_sn === "string" && /^DL-/i.test(next.bms_sn)) {
    next.bms_sn = "";
  }
  return next;
}

function repairBatteryIdentities(db) {
  db.exec("PRAGMA foreign_keys = OFF");
  try {
    const rename = db.transaction(() => {
    const rows = db.prepare("SELECT bms_uid FROM batteries").all();
    for (const row of rows) {
      const clean = sanitizeIdentity(row.bms_uid);
      if (!clean || clean === row.bms_uid) continue;
      const clash = db.prepare("SELECT bms_uid FROM batteries WHERE bms_uid = ?").get(clean);
      const childTables = ["telemetry", "config_checks", "write_commands", "service_reports"];
      if (clash) {
        for (const table of childTables) {
          db.prepare(`UPDATE ${table} SET bms_uid = ? WHERE bms_uid = ?`).run(clean, row.bms_uid);
        }
        db.prepare("DELETE FROM batteries WHERE bms_uid = ?").run(row.bms_uid);
      } else {
        db.prepare("UPDATE batteries SET bms_uid = ? WHERE bms_uid = ?").run(clean, row.bms_uid);
        for (const table of childTables) {
          db.prepare(`UPDATE ${table} SET bms_uid = ? WHERE bms_uid = ?`).run(clean, row.bms_uid);
        }
      }
    }

    const batteries = db.prepare(`
      SELECT bms_uid, bluetooth_name, advertised_name, bms_sn, assembler_name, bluetooth_id
      FROM batteries
    `).all();
    const updateBattery = db.prepare(`
      UPDATE batteries
      SET bluetooth_name = @bluetooth_name,
          advertised_name = @advertised_name,
          bms_sn = @bms_sn,
          assembler_name = @assembler_name,
          bluetooth_id = @bluetooth_id
      WHERE bms_uid = @bms_uid
    `);
    for (const battery of batteries) {
      updateBattery.run({
        bms_uid: battery.bms_uid,
        bluetooth_name: sanitizeIdentity(battery.bluetooth_name),
        advertised_name: sanitizeIdentity(battery.advertised_name),
        bms_sn: sanitizeIdentity(battery.bms_sn),
        assembler_name: sanitizeIdentity(battery.assembler_name),
        bluetooth_id: sanitizeIdentity(battery.bluetooth_id),
      });
    }
  });
    rename();
  } finally {
    db.exec("PRAGMA foreign_keys = ON");
  }
}

function inferHardwareFamily(_payload) {
  return "standard";
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function batteryIdentity(payload) {
  return {
    bms_uid: sanitizeIdentity(payload.bms_uid) || payload.bms_uid,
    bluetooth_name: sanitizeIdentity(payload.bluetooth_name) ?? payload.bluetooth_name ?? null,
    bluetooth_address: emptyToNull(payload.bluetooth_address),
    advertised_name: sanitizeIdentity(payload.advertised_name),
    hardware_family: inferHardwareFamily(payload),
    owner_name: emptyToNull(payload.owner_name),
    owner_phone: emptyToNull(payload.owner_phone),
    owner_email: emptyToNull(payload.owner_email),
    last_source: payload.source === "service" ? "service" : payload.source === "user" ? "user" : null,
    assembler_name: sanitizeIdentity(payload.assembler_name),
    bms_sn: sanitizeIdentity(payload.bms_sn),
    bluetooth_id: sanitizeIdentity(payload.bluetooth_id) ?? emptyToNull(payload.bluetooth_address),
  };
}

function isSafeTimestamp(value) {
  return Number.isSafeInteger(value) && value > 0;
}

module.exports = { openDatabase };
