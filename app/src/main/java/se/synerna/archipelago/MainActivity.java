package se.synerna.archipelago;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;


/**
 * An activity representing a list of Islands. This activity
 * has different presentations for handset and tablet-size devices. On
 * handsets, the activity presents a list of items, which when touched,
 * lead to a {@link IslandDetailActivity} representing
 * item details. On tablets, the activity presents the list of items and
 * item details side-by-side using two vertical panes.
 * <p/>
 * The activity makes use of fragments. The list of items is a
 * {@link IslandListFragment} and the item details
 * (if present) is a {@link IslandDetailFragment}.
 * <p/>
 * This activity also implements the required
 * {@link IslandListFragment.Callbacks} interface
 * to listen for item selections.
 */
public class MainActivity extends FragmentActivity
        implements IslandListFragment.Callbacks {

    /**
     * Whether or not the activity is in two-pane mode, i.e. running on a tablet
     * device.
     */
    private boolean mTwoPane;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_island_list);

        if (findViewById(R.id.island_detail_container) != null) {
            // If we use a tablet
            mTwoPane = true;

            // In two-pane mode, list items should be given the
            // 'activated' state when touched.
            // That would trigger some coloring (for instance)
            ((IslandListFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.island_list))
                    .setActivateOnItemClick(true);
        }

        // TODO: If exposing deep links into your app, handle intents here.
    }

    /**
     * Callback method from {@link IslandListFragment.Callbacks}
     * indicating that the item with the given ID was selected.
     */
    @Override
    public void onItemSelected(String id) {
        if (mTwoPane) {
            // In two-pane mode, show the detail view in this activity by
            // adding or replacing the detail fragment using a
            // fragment transaction.
            Bundle arguments = new Bundle();
            arguments.putString(IslandDetailFragment.ARG_ITEM_ID, id);
            IslandDetailFragment fragment = new IslandDetailFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.island_detail_container, fragment)
                    .commit();

        } else {
            // In single-pane mode, simply start the detail activity
            // for the selected item ID.
            Intent detailIntent = new Intent(this, IslandDetailActivity.class);
            detailIntent.putExtra(IslandDetailFragment.ARG_ITEM_ID, id);
            startActivity(detailIntent);
        }
    }
}
