package kr.kro.backas.certification;

import java.util.Objects;

public record CertificationInfo(String email, long date, String knownAs, boolean univCheck) {

    public static CertificationInfo email(String email) {
        return new CertificationInfo(email, 0L, null, false);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof String e) return e.equals(email());
        return false;
    }
}
