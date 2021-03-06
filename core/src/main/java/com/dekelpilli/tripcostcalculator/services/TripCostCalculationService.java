package com.dekelpilli.tripcostcalculator.services;

import com.dekelpilli.tripcostcalculator.calcluators.TripCostCalculator;
import com.dekelpilli.tripcostcalculator.configurations.TripCostCalculatorConfiguration;
import com.dekelpilli.tripcostcalculator.factories.TripCostCalculatorFactory;
import com.dekelpilli.tripcostcalculator.io.CsvFileReader;
import com.dekelpilli.tripcostcalculator.io.CsvFileWriter;
import com.dekelpilli.tripcostcalculator.model.Tap;
import com.dekelpilli.tripcostcalculator.model.Trip;
import com.dekelpilli.tripcostcalculator.model.TripStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Service
public class TripCostCalculationService {

    private final CsvFileReader csvFileReader;
    private final CsvFileWriter csvFileWriter;
    private final TripCostCalculatorFactory tripCostCalculatorFactory;

    private final String inputFileName;
    private final String outputFileName;
    private final String currencySymbol;

    public TripCostCalculationService(TripCostCalculatorConfiguration tripCostCalculatorConfiguration,
                                      TripCostCalculatorFactory tripCostCalculatorFactory,
                                      CsvFileReader csvFileReader, CsvFileWriter csvFileWriter) {
        inputFileName = tripCostCalculatorConfiguration.getInput();
        outputFileName = tripCostCalculatorConfiguration.getOutput();
        currencySymbol = tripCostCalculatorConfiguration.getCurrencySymbol();

        this.csvFileReader = csvFileReader;
        this.csvFileWriter = csvFileWriter;
        this.tripCostCalculatorFactory = tripCostCalculatorFactory;
    }

    private static long calculateTripDurationInSeconds(Date tapOnTime, Date tapOffTime) {
        return TimeUnit.MILLISECONDS.toSeconds(tapOffTime.getTime() - tapOnTime.getTime());
    }

    private static TripStatus getTripStatus(Tap tapOn, Tap tapOff) {
        if (tapOn.getStopId().equals(tapOff.getStopId())) {
            return TripStatus.CANCELLED;
        }
        return TripStatus.COMPLETED;
    }

    public void calculateTripCosts() throws IOException {
        Iterator<Tap> taps = csvFileReader.parse(inputFileName, Tap.class);

        Map<String, Tap> tappedOnUsers = new HashMap<>();
        List<Trip> calculatedTrips = new ArrayList<>();
        List<Tap> tapOnsForIncompleteTrips = new ArrayList<>();

        taps.forEachRemaining(tap -> {
            String primaryAccountNumber = tap.getPrimaryAccountNumber();
            switch (tap.getTapType()) {
                case ON:
                    if (tappedOnUsers.containsKey(primaryAccountNumber)) {
                        tapOnsForIncompleteTrips.add(tappedOnUsers.get(primaryAccountNumber));
                    }
                    tappedOnUsers.put(primaryAccountNumber, tap);
                    break;
                case OFF:
                    Tap tapOn = tappedOnUsers.get(primaryAccountNumber);
                    Trip trip = createTripFromTapPair(tapOn, tap);
                    calculatedTrips.add(trip);
                    tappedOnUsers.remove(primaryAccountNumber);
                    break;
            }
        });
        tapOnsForIncompleteTrips.addAll(tappedOnUsers.values());
        tapOnsForIncompleteTrips.forEach(tapOn ->
                calculatedTrips.add(createTripFromTapPair(tapOn, null, TripStatus.INCOMPLETE))
        );
        calculatedTrips.sort(Comparator.comparing(Trip::getStartedTime));
        csvFileWriter.createFile(outputFileName, calculatedTrips, Trip.class);
    }

    private Trip createTripFromTapPair(Tap tapOn, @Nullable Tap tapOff, TripStatus tripStatus) {
        Trip trip = new Trip();
        Date tapOnTime = tapOn.getTapTime();
        trip.setStartedTime(tapOnTime);
        if (tapOff != null) {
            Date tapOffTime = tapOff.getTapTime();
            trip.setFinishedTime(tapOffTime);
            trip.setDurationSeconds(calculateTripDurationInSeconds(tapOnTime, tapOffTime));

            trip.setToStopId(tapOff.getStopId());
        }

        trip.setBusId(tapOn.getBusId());
        trip.setFromStopId(tapOn.getStopId());
        trip.setCompanyId(tapOn.getCompanyId());
        trip.setPrimaryAccountNumber(tapOn.getPrimaryAccountNumber());

        TripCostCalculator tripCostCalculator = tripCostCalculatorFactory.getCalculatorForStatus(tripStatus);
        trip.setChargeAmount(currencySymbol + tripCostCalculator.calculateChargeAmount(tapOn, tapOff)
                .setScale(2, RoundingMode.HALF_UP));
        trip.setTripStatus(tripStatus);
        return trip;
    }

    private Trip createTripFromTapPair(Tap tapOn, Tap tapOff) {
        return createTripFromTapPair(tapOn, tapOff, getTripStatus(tapOn, tapOff));
    }
}
