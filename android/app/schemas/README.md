# Database schema history

Room exports schemas here during KSP compilation. The initial v3 snapshot was
recovered from the existing Room-generated `BruciaDb_Impl.java`, including its
identity hash, table creation SQL and validated column metadata. It does not
reconstruct unknown historical schemas. Commit generated JSON when a
schema is first exported or changed. The current database stays at version 3;
there is no schema/version change in this reliability work.

Destructive migration fallback has been removed: an unsupported older schema must
fail rather than delete research data. No v1/v2 schema can be recovered from this
repository's history; do not invent migrations for them. Obtain the actual old
schema/database before adding a migration and test that captured data survives.
