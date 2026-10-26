package io.github.chutian0610.jolt.bind.fixtures;

/** Package-private POJO: exercises the generated-binder fallback (non-public target). */
class HiddenPojo {

    private int v;

    public HiddenPojo() {}

    public void setV(int v) {
        this.v = v;
    }

    public int getV() {
        return v;
    }
}
