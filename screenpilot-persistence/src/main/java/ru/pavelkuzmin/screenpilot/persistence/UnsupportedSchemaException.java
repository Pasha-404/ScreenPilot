package ru.pavelkuzmin.screenpilot.persistence;

public final class UnsupportedSchemaException extends PersistenceException {
    public UnsupportedSchemaException(String fileName, int schemaVersion, int supportedSchemaVersion) {
        super(fileName + " uses schema " + schemaVersion + ", but this application supports up to " + supportedSchemaVersion);
    }
}
