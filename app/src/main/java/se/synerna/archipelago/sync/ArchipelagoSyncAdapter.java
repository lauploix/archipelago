package se.synerna.archipelago.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
//import android.app.NotificationManager;
//import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
//import android.content.Intent;
//import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
//import android.preference.PreferenceManager;
//import android.support.v4.app.NotificationCompat;
//import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

//import se.synerna.archipelago.MainActivity;
import se.synerna.archipelago.R;
import se.synerna.archipelago.Utility;
import se.synerna.archipelago.data.ArchipelagoContract;
import se.synerna.archipelago.data.ArchipelagoContract.WeatherEntry;
import se.synerna.archipelago.data.ArchipelagoContract.LocationEntry;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Vector;

public class ArchipelagoSyncAdapter extends AbstractThreadedSyncAdapter {
    public static final String LOG_TAG = ArchipelagoSyncAdapter.class.getSimpleName();
    public static final int SYNC_INTERVAL = 10; //60 * 180;
    public static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    public ArchipelagoSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    private void syncIslandsLocations(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String islandsStr = null;
        try {
            // Construct the URL for the OpenWeatherMap query
            // http://openweathermap.org/API#forecast
            final String ISLANDS_URL =
                    "https://raw.githubusercontent.com/lauploix/archipelago/master/data/islands.json";
            URL url = new URL(ISLANDS_URL);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a String
            InputStream inputStream = urlConnection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                // There should be a file there...
                // for now we return and we do nothing
                return;
            }

            // Does it need to be that complicated ?
            // Well, anyway... it does not hurt
            reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                buffer.append(line + "\n");
            }

            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return;
            }
            islandsStr = buffer.toString();

            // Log.v(LOG_TAG, islandsStr); // Found this json string
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attempting
            // to parse it.
            return;
        } finally {
            if (urlConnection != null) {
                // Good practice
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    // Log but go on
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
        }
        // Here the json containing the islands is available.
        // The same would have costs approx 5 loc in python... just me saying.

        long[] locationIds = null;
        double[] latitudes = null;
        double[] longitudes = null;

        try {
            final String JSON_ISLANDS = "islands";
            final String JSON_NAME = "name";
            final String JSON_LONG = "long";
            final String JSON_LAT = "lat";

            JSONObject islandJson = new JSONObject(islandsStr);
            JSONArray islandsArray = islandJson.getJSONArray(JSON_ISLANDS);

            locationIds = new long[islandsArray.length()];
            latitudes = new double[islandsArray.length()];
            longitudes = new double[islandsArray.length()];

            for (int i = 0; i < islandsArray.length(); i++) {
                JSONObject island = islandsArray.getJSONObject(i);
                String name = island.getString(JSON_NAME);
                double longi = island.getDouble(JSON_LONG);
                double lati = island.getDouble(JSON_LAT);

                locationIds[i] = addLocation(name, lati, longi);
            }

        } catch (JSONException e) {
            // Problem parsing the Json that is returned
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        }

        // as values, we have ids, longitudes, latitudes in 3 arrays to use to fetch weather from there

        // URL for current weather by coordinates : "http://api.openweathermap.org/data/2.5/weather?lat=35&lon=139"

        for (int i = 0; i < locationIds.length; i++) {
            updateCurrentWeatherFromApi(locationIds[i], latitudes[i], longitudes[i]);
        }
    }

    /**
     * @param locationId id in the database.
     * @param latitude   the latitude of the place
     * @param longitude  the longitude of the place
     * @return nothing
     */
    private void updateCurrentWeatherFromApi(Long locationId, Double latitude, double longitude) {

        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String forecastJsonStr = null;

        String format = "json";
        String units = "metric";

        try {
            // Construct the URL for the OpenWeatherMap query
            // http://openweathermap.org/API#forecast
            final String FORECAST_BASE_URL =
                    "http://api.openweathermap.org/data/2.5/weather?";
            final String LAT_PARAM = "lat";
            final String LONG_PARAM = "lon";
            final String FORMAT_PARAM = "mode";
            final String UNITS_PARAM = "units";

            Uri builtUri = Uri.parse(FORECAST_BASE_URL).buildUpon()
                    .appendQueryParameter(LAT_PARAM, Double.toString(latitude))
                    .appendQueryParameter(LONG_PARAM, Double.toString(longitude))
                    .appendQueryParameter(FORMAT_PARAM, format) // json
                    .appendQueryParameter(UNITS_PARAM, units) // metric
                    .build();

            URL url = new URL(builtUri.toString());

            // Create the request to OpenWeatherMap, and open the connection
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a String
            InputStream inputStream = urlConnection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                // Nothing to do.
                return;
            }

            reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                // But it does make debugging a *lot* easier if you print out the completed
                // buffer for debugging.
                buffer.append(line + "\n");
            }

            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return;
            }
            forecastJsonStr = buffer.toString();

            Log.v(LOG_TAG, forecastJsonStr);

        } catch (IOException e) {
            Log.e(LOG_TAG, "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attempting
            // to parse it.
            return;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(LOG_TAG, "Error closing stream", e);
                }
            }
        }

        final String JSON_WIND = "wind";
        final String JSON_DATE = "dt";
        final String JSON_WINDSPEED = "speed";
        final String JSON_WIND_DIRECTION = "deg";

        try {
            Calendar calendar = GregorianCalendar.getInstance(); // creates a new calendar instance

            JSONObject forecastJson = new JSONObject(forecastJsonStr);
            JSONObject windJson = forecastJson.getJSONObject(JSON_WIND);

            double windSpeed;
            double windDirection;
            long dateTime;

            windSpeed = windJson.getDouble(JSON_WINDSPEED);
            windDirection = windJson.getDouble(JSON_WIND_DIRECTION);
            dateTime = forecastJson.getLong(JSON_DATE);
            calendar.setTime(new Date(dateTime * 1000L));   // assigns calendar to given date

            int dateHour = calendar.get(Calendar.HOUR_OF_DAY); // gets hour in 24h format;

            ContentValues weatherValues = new ContentValues();

            weatherValues.put(WeatherEntry.COLUMN_LOC_KEY, locationId);
            weatherValues.put(WeatherEntry.COLUMN_WIND_SPEED, windSpeed);
            weatherValues.put(WeatherEntry.COLUMN_DEGREES, windDirection);
            weatherValues.put(WeatherEntry.COLUMN_HOUR, dateHour);

            getContext().getContentResolver().insert(WeatherEntry.CONTENT_URI, weatherValues);

        } catch (JSONException e) {
            // Problem parsing the Json that is returned
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        }

        return;

    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String
            authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(LOG_TAG, "Starting sync");

        syncIslandsLocations(account, extras, authority, provider, syncResult);

    }


    /**
     * Helper method to handle insertion of a new location in the weather database.
     *
     * @param locationSetting The location string used to request updates from the server.
     * @param lat             the latitude of the city
     * @param lon             the longitude of the city
     * @return the row ID of the added location.
     */

    private long addLocation(String locationSetting, double lat, double lon) {
        long locationId;

        Log.v(LOG_TAG, "inserting " + locationSetting + ", with coord: " + lat + ", " + lon);

        // First, check if the location with this city name exists in the db
        Cursor locationCursor = getContext().getContentResolver().query(
                ArchipelagoContract.LocationEntry.CONTENT_URI,
                new String[]{LocationEntry._ID},
                LocationEntry.COLUMN_LOCATION_NAME + " = ?",
                new String[]{locationSetting},
                null);

        if (locationCursor.moveToFirst()) {
            int locationIdIndex = locationCursor.getColumnIndex(LocationEntry._ID);
            locationId = locationCursor.getLong(locationIdIndex);
        } else {
            // Now that the content provider is set up, inserting rows of data is pretty simple.
            // First create a ContentValues object to hold the data you want to insert.
            ContentValues locationValues = new ContentValues();

            // Then add the data, along with the corresponding name of the data type,
            // so the content provider knows what kind of value is being inserted.
            locationValues.put(LocationEntry.COLUMN_LOCATION_NAME, locationSetting);
            locationValues.put(LocationEntry.COLUMN_COORD_LAT, lat);
            locationValues.put(LocationEntry.COLUMN_COORD_LONG, lon);

            // Finally, insert location data into the database.
            Uri insertedUri = getContext().getContentResolver().insert(
                    ArchipelagoContract.LocationEntry.CONTENT_URI,
                    locationValues
            );

            // The resulting URI contains the ID for the row.  Extract the locationId from the Uri.
            locationId = ContentUris.parseId(insertedUri);
        }
        locationCursor.close();
        return locationId;
    }


    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        ContentResolver.cancelSync(account, authority); // Start by removing existing ones

        // As of now we schedule periodic syncs even when the user already has such syncs
        // @todo: how to remove previously created sync requests first ?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync

            // @todo Check what the bundle is for. Put an empty one for now.

            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setExtras(new Bundle()).
                    setSyncAdapter(account, authority).build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }


    /**
     * Helper method to have the sync adapter sync immediately
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }

    /**
     * Get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.
     *
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if (null == accountManager.getPassword(newAccount)) {
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
        }
        return newAccount;
    }

    public static void initializeSyncAdapter(Context context) {
        Account account = getSyncAccount(context);
        ContentResolver.setSyncAutomatically(account, context.getString(R.string.content_authority), true);
        configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);
        syncImmediately(context);
    }


}
