const fs = require("node:fs");
const path = require("node:path");
const Database = require("better-sqlite3");

function openDatabase(filename) {
  if (filename !== ":memory:") {
    fs.mkdirSync(path.dirname(filename), { recursive: true });
  }

  const db = new Database(filename);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");

  db.exec(`
    CREATE TABLE IF NOT EXISTS batteries (
      bms_uid TEXT PRIMARY KEY,
      bluetooth_name TEXT,
      bluetooth_address TEXT,
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
  `);

  const upsertBattery = db.prepare(`
    INSERT INTO batteries (
      bms_uid, bluetooth_name, bluetooth_address, first_seen_at, last_seen_at
    ) VALUES (
      @bms_uid, @bluetooth_name, @bluetooth_address, @received_at, @received_at
    )
    ON CONFLICT(bms_uid) DO UPDATE SET
      bluetooth_name = COALESCE(excluded.bluetooth_name, batteries.bluetooth_name),
      bluetooth_address = COALESCE(excluded.bluetooth_address, batteries.bluetooth_address),
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
      bms_uid: payload.bms_uid,
      bluetooth_name: payload.bluetooth_name ?? null,
      bluetooth_address: payload.bluetooth_address ?? null,
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
      bms_uid: payload.bms_uid,
      bluetooth_name: payload.bluetooth_name ?? null,
      bluetooth_address: payload.bluetooth_address ?? null,
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

    hasBattery(bmsUid) {
      return db.prepare("SELECT 1 FROM batteries WHERE bms_uid = ?").get(bmsUid) != null;
    },

    listBatteries() {
      const rows = db.prepare(`
        SELECT
          b.bms_uid,
          b.bluetooth_name,
          b.bluetooth_address,
          b.first_seen_at,
          b.last_seen_at,
          t.id AS telemetry_id,
          t.received_at,
          t.voltage,
          t.current,
          t.soc,
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

function booleanToInteger(value) {
  if (value === true) return 1;
  if (value === false) return 0;
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

module.exports = { openDatabase };
