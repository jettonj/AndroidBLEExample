package io.particle.bleexample;

public class OriSystemModel {
    String systemName;
    int image;

    public OriSystemModel(String systemName, int image) {
        this.systemName = systemName;
        this.image = image;
    }

    public String getSystemName() {
        return systemName;
    }

    public int getImage() {
        return image;
    }
}
