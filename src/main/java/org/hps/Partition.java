package org.hps;

import java.util.Objects;

public class Partition implements Comparable<Partition> {
    private Long lag;
    private  int id;
    public Partition(int id, Long lag) {
        this.id= id;
        this.lag = lag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Partition partition = (Partition) o;
        return lag.equals(partition.lag);
    }

    public int getId() {
        return id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lag);
    }

    public Long getLag() {
        return lag;
    }

    public void setLag(Long lag) {
        this.lag = lag;
    }

    @Override
    public String toString() {
        return "Partition{" +
                "lag=" + lag +
                '}';
    }

    @Override
    public int compareTo(Partition o) {
        return Long.compare(this.lag , o.lag);
    }
}


