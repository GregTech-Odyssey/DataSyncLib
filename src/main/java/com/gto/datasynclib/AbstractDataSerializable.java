package com.gto.datasynclib;

public abstract class AbstractDataSerializable implements IDataSerializable {

    protected boolean syncChange;

    @Override
    public void markAsChanged() {
        syncChange = true;
    }

    @Override
    public void clearChanged() {
        syncChange = false;
    }

    @Override
    public boolean isChanged() {
        return syncChange;
    }

    @Override
    public boolean detectChange() {
        return syncChange;
    }
}
