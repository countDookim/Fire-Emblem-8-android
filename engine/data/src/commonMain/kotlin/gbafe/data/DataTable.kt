package gbafe.data

/**
 * A compiled, read-only game data table (items, classes, characters, ...).
 *
 * Source of truth is versioned, schema-validated JSON in the VFS (`data/<table>.json` +
 * `schemas/<table>.schema.json`). At load time it is compiled into primitive arrays, and typed
 * accessors such as `ItemTable.might(id)` read those arrays directly. See
 * `docs/decisions/0002-data-schemas.md`. Concrete tables arrive with milestone 2.
 */
public interface DataTable {
    /** Table identifier, equal to its JSON file stem, e.g. `items`. */
    public val name: String

    /** JSON format version this table was compiled from. */
    public val schemaVersion: Int

    /** Number of rows (valid ids are `0 until size`). */
    public val size: Int
}
