package com.freightfox.dispatch.testdata;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates reproducible, realistic orders and vehicles around {@link IndianCity real Indian city
 * centres}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Every point picks a city uniformly at random, then moves up to {@value #MAX_OFFSET_KM} km
 *       away from its centre in a random direction (uniform over the disc). Coordinates are
 *       rounded to {@value #COORDINATE_DECIMALS} decimals and always stay within valid ranges.</li>
 *   <li>Address: "Near &lt;City&gt;, India".</li>
 *   <li>Priority: 30% HIGH, 40% MEDIUM, 30% LOW (independent random draw per order).</li>
 *   <li>Package weight uniform in [{@value #MIN_PACKAGE_WEIGHT}, {@value #MAX_PACKAGE_WEIGHT}];
 *       capacity uniform in [{@value #MIN_CAPACITY}, {@value #MAX_CAPACITY}].</li>
 *   <li>IDs are zero padded so they sort lexicographically: ORD000001.., VEH0001..</li>
 * </ul>
 *
 * <p>Determinism: the same {@code seed} always yields the same dataset. Orders and vehicles use
 * two independent streams derived from {@code new Random(seed)}, so the vehicles for a given seed
 * do not change when only the order count changes (and vice versa). The class is stateless and
 * thread-safe.
 */
@Component
public class TestDataGenerator {

    public static final String ORDER_ID_PREFIX = "ORD";
    public static final String VEHICLE_ID_PREFIX = "VEH";
    /** 6 digits cover up to 999,999 orders (the API allows 50,000). */
    public static final int ORDER_ID_DIGITS = 6;
    /** 4 digits cover up to 9,999 vehicles (the API allows 2,000). */
    public static final int VEHICLE_ID_DIGITS = 4;
    public static final int MAX_ORDER_COUNT = 999_999;
    public static final int MAX_VEHICLE_COUNT = 9_999;

    public static final double MAX_OFFSET_KM = 15.0;
    public static final int MIN_PACKAGE_WEIGHT = 1;
    public static final int MAX_PACKAGE_WEIGHT = 50;
    public static final int MIN_CAPACITY = 100;
    public static final int MAX_CAPACITY = 500;
    public static final int COORDINATE_DECIMALS = 6;

    /** Percent of orders that are HIGH; the next {@link #MEDIUM_PERCENT} are MEDIUM, the rest LOW. */
    public static final int HIGH_PERCENT = 30;
    public static final int MEDIUM_PERCENT = 40;

    private static final String ADDRESS_PREFIX = "Near ";
    private static final String ADDRESS_SUFFIX = ", India";
    /** Kilometres per degree of latitude (and of longitude at the equator). */
    private static final double KM_PER_DEGREE = 111.32;
    private static final double COORDINATE_SCALE = Math.pow(10, COORDINATE_DECIMALS);
    private static final double MAX_LATITUDE = 90.0;
    private static final double MAX_LONGITUDE = 180.0;
    private static final double FULL_CIRCLE_DEGREES = 360.0;
    private static final int PERCENT = 100;
    private static final IndianCity[] CITIES = IndianCity.values();

    /**
     * Generates a dataset.
     *
     * @param orderCount   number of orders, 0..{@value #MAX_ORDER_COUNT}
     * @param vehicleCount number of vehicles, 0..{@value #MAX_VEHICLE_COUNT}
     * @param seed         random seed; the same seed gives the same data
     * @return orders and vehicles sorted by ID
     * @throws IllegalArgumentException if a count is negative or too large for the ID format
     */
    public GeneratedDataset generate(int orderCount, int vehicleCount, long seed) {
        requireCount("orderCount", orderCount, MAX_ORDER_COUNT);
        requireCount("vehicleCount", vehicleCount, MAX_VEHICLE_COUNT);

        Random root = new Random(seed);
        Random orderRandom = new Random(root.nextLong());
        Random vehicleRandom = new Random(root.nextLong());

        List<DeliveryOrder> orders = new ArrayList<>(orderCount);
        for (int i = 1; i <= orderCount; i++) {
            orders.add(newOrder(i, orderRandom));
        }
        List<Vehicle> vehicles = new ArrayList<>(vehicleCount);
        for (int i = 1; i <= vehicleCount; i++) {
            vehicles.add(newVehicle(i, vehicleRandom));
        }
        return new GeneratedDataset(orders, vehicles);
    }

    /** Formats an order ID, e.g. 1 -> "ORD000001". */
    public static String orderId(int sequence) {
        return ORDER_ID_PREFIX + zeroPad(sequence, ORDER_ID_DIGITS);
    }

    /** Formats a vehicle ID, e.g. 1 -> "VEH0001". */
    public static String vehicleId(int sequence) {
        return VEHICLE_ID_PREFIX + zeroPad(sequence, VEHICLE_ID_DIGITS);
    }

    /** The address used for a point near {@code city}: "Near &lt;City&gt;, India". */
    public static String addressNear(IndianCity city) {
        return ADDRESS_PREFIX + city.displayName() + ADDRESS_SUFFIX;
    }

    private DeliveryOrder newOrder(int sequence, Random random) {
        Location location = randomLocation(random);
        int weight = uniform(random, MIN_PACKAGE_WEIGHT, MAX_PACKAGE_WEIGHT);
        Priority priority = randomPriority(random);
        return new DeliveryOrder(orderId(sequence), location.latitude(), location.longitude(),
                addressNear(location.city()), weight, priority);
    }

    private Vehicle newVehicle(int sequence, Random random) {
        Location location = randomLocation(random);
        int capacity = uniform(random, MIN_CAPACITY, MAX_CAPACITY);
        return new Vehicle(vehicleId(sequence), capacity, location.latitude(), location.longitude(),
                addressNear(location.city()));
    }

    /** A city chosen uniformly, then a point uniformly distributed in a 15 km disc around it. */
    private static Location randomLocation(Random random) {
        IndianCity city = CITIES[random.nextInt(CITIES.length)];
        // sqrt makes the points uniform over the disc's area instead of clustering at the centre.
        double distanceKm = MAX_OFFSET_KM * Math.sqrt(random.nextDouble());
        double bearing = random.nextDouble() * 2 * Math.PI;
        double latRad = Math.toRadians(city.latitude());
        double dLat = distanceKm * Math.cos(bearing) / KM_PER_DEGREE;
        double dLon = distanceKm * Math.sin(bearing) / (KM_PER_DEGREE * Math.cos(latRad));
        double latitude = round(clampLatitude(city.latitude() + dLat));
        double longitude = round(normalizeLongitude(city.longitude() + dLon));
        return new Location(city, latitude, longitude);
    }

    private static Priority randomPriority(Random random) {
        int draw = random.nextInt(PERCENT);
        if (draw < HIGH_PERCENT) {
            return Priority.HIGH;
        }
        if (draw < HIGH_PERCENT + MEDIUM_PERCENT) {
            return Priority.MEDIUM;
        }
        return Priority.LOW;
    }

    /** Uniform integer in [min, max] (both inclusive). */
    private static int uniform(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    /** Wraps a longitude into [-180, 180]. */
    static double normalizeLongitude(double longitude) {
        if (longitude >= -MAX_LONGITUDE && longitude <= MAX_LONGITUDE) {
            return longitude;
        }
        double wrapped = ((longitude + MAX_LONGITUDE) % FULL_CIRCLE_DEGREES + FULL_CIRCLE_DEGREES)
                % FULL_CIRCLE_DEGREES;
        return wrapped - MAX_LONGITUDE;
    }

    private static double round(double value) {
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
    }

    private static String zeroPad(int sequence, int digits) {
        String number = Integer.toString(sequence);
        if (number.length() >= digits) {
            return number;
        }
        return "0".repeat(digits - number.length()) + number;
    }

    private static void requireCount(String name, int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException(name + " must be between 0 and " + max + " but was " + count);
        }
    }

    /** A generated point and the city it was placed around. */
    private record Location(IndianCity city, double latitude, double longitude) {
    }
}
