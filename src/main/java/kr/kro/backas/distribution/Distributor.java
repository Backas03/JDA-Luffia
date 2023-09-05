package kr.kro.backas.distribution;

import net.dv8tion.jda.api.EmbedBuilder;

import java.util.Map;

public class Distributor {

    private final RandomDistribution distribution;
    private Map<Member, String> result;

    public Distributor(RandomDistribution distribution) {
        this.distribution = distribution;
    }

    public Distributor distribute() {

        return this;
    }

    public EmbedBuilder result() {

        return null;
    }

}
