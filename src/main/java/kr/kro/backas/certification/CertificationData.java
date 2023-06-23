package kr.kro.backas.certification;

import net.dv8tion.jda.api.entities.User;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class CertificationData {

    private final Map<Long, CertificationInfo> data = new HashMap<>();

    public CertificationInfo getCertificationInfo(long userId) {
        return data.get(userId);
    }

    public CertificationInfo getCertificationInfo(User user) {
        return getCertificationInfo(user.getIdLong());
    }

    public boolean isCertificated(long userId) {
        return data.containsKey(userId);
    }

    public boolean isCertificated(User user) {
        return isCertificated(user.getIdLong());
    }

    public boolean isCertificated(String email) {
        Collection<CertificationInfo> infos = data.values();
        for (CertificationInfo info : infos) {
            if (info.email().equals(email)) return true;
        }
        return false;
    }
    public Map<Long, CertificationInfo> getData() {
        return data;
    }
}
