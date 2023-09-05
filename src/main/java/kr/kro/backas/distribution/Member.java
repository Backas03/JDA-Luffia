package kr.kro.backas.distribution;

import java.util.Objects;

public record Member(String name, int weight) {

    public static Member onlyName(String name) {
        return new Member(name, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Member compare)) return false;
        return name.equals(compare.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
