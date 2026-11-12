package com.freightfox.dispatch.testdata;

/**
 * Thirty real Indian city centres used as anchors for generated test data. Coordinates are the
 * commonly published city-centre coordinates in decimal degrees (WGS84).
 */
public enum IndianCity {
    DELHI("Delhi", 28.6139, 77.2090),
    MUMBAI("Mumbai", 19.0760, 72.8777),
    BANGALORE("Bangalore", 12.9716, 77.5946),
    CHENNAI("Chennai", 13.0827, 80.2707),
    KOLKATA("Kolkata", 22.5726, 88.3639),
    HYDERABAD("Hyderabad", 17.3850, 78.4867),
    PUNE("Pune", 18.5204, 73.8567),
    AHMEDABAD("Ahmedabad", 23.0225, 72.5714),
    JAIPUR("Jaipur", 26.9124, 75.7873),
    LUCKNOW("Lucknow", 26.8467, 80.9462),
    SURAT("Surat", 21.1702, 72.8311),
    KANPUR("Kanpur", 26.4499, 80.3319),
    NAGPUR("Nagpur", 21.1458, 79.0882),
    INDORE("Indore", 22.7196, 75.8577),
    BHOPAL("Bhopal", 23.2599, 77.4126),
    VISAKHAPATNAM("Visakhapatnam", 17.6868, 83.2185),
    PATNA("Patna", 25.5941, 85.1376),
    VADODARA("Vadodara", 22.3072, 73.1812),
    LUDHIANA("Ludhiana", 30.9010, 75.8573),
    AGRA("Agra", 27.1767, 78.0081),
    NASHIK("Nashik", 19.9975, 73.7898),
    COIMBATORE("Coimbatore", 11.0168, 76.9558),
    KOCHI("Kochi", 9.9312, 76.2673),
    THIRUVANANTHAPURAM("Thiruvananthapuram", 8.5241, 76.9366),
    CHANDIGARH("Chandigarh", 30.7333, 76.7794),
    GUWAHATI("Guwahati", 26.1445, 91.7362),
    BHUBANESWAR("Bhubaneswar", 20.2961, 85.8245),
    MYSURU("Mysuru", 12.2958, 76.6394),
    MADURAI("Madurai", 9.9252, 78.1198),
    VARANASI("Varanasi", 25.3176, 82.9739);

    private final String displayName;
    private final double latitude;
    private final double longitude;

    IndianCity(String displayName, double latitude, double longitude) {
        this.displayName = displayName;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Human readable name, e.g. "Bangalore". */
    public String displayName() {
        return displayName;
    }

    /** City-centre latitude in decimal degrees. */
    public double latitude() {
        return latitude;
    }

    /** City-centre longitude in decimal degrees. */
    public double longitude() {
        return longitude;
    }
}
