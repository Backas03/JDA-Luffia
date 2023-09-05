package kr.kro.backas.distribution;

import java.util.HashSet;
import java.util.Set;

public class RandomDistribution {

    private final Set<Member> members;
    private final Set<String> teams;

    public RandomDistribution() {
        this.members = new HashSet<>();
        this.teams = new HashSet<>();
    }

    public void addTeam(String name) throws DistributionException {
        if (teams.contains(name)) throw new DistributionException("이미 해당 팀이 추가된 상태입니다");
        teams.add(name);
    }

    public void join(String name, int weight) throws DistributionException {
        Member member = new Member(name, weight);
        if (members.contains(member)) {
            throw new DistributionException("이미 추가된 맴버입니다");
        }
        members.add(member);
    }

    public void quit(String name) throws DistributionException {
        Member member = Member.onlyName(name);
        if (!members.contains(member)) {
            throw new DistributionException("추가되지 않은 맴버입니다");
        }
        members.remove(member);
    }

    public Distributor distributor() {
        return new Distributor(this);
    }
}
