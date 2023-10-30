package kr.kro.backas.command.api;

public enum CommandType {
    CERTIFICATION("인증");

    private final String name;

    public String getName() {
        return name;
    }

    CommandType(String name) {
        this.name = name;
    }
}
