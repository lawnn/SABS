package com.layoutxml.sabs.blocker;

import android.support.annotation.Nullable;
import android.util.Log;

import com.layoutxml.sabs.App;
import com.layoutxml.sabs.MainActivity;
import com.layoutxml.sabs.db.AppDatabase;
import com.layoutxml.sabs.db.entity.AppInfo;
import com.layoutxml.sabs.db.entity.BlockUrl;
import com.layoutxml.sabs.db.entity.BlockUrlProvider;
import com.layoutxml.sabs.db.entity.UserBlockUrl;
import com.layoutxml.sabs.db.entity.WhiteUrl;
import com.layoutxml.sabs.utils.BlockUrlPatternsMatch;
import com.layoutxml.sabs.utils.SplitList;
import com.sec.enterprise.AppIdentity;
import com.sec.enterprise.firewall.DomainFilterRule;
import com.sec.enterprise.firewall.Firewall;
import com.sec.enterprise.firewall.FirewallResponse;
import com.sec.enterprise.firewall.FirewallRule;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;

import javax.inject.Inject;

import static com.layoutxml.sabs.Global.BlockPort53;
import static com.layoutxml.sabs.Global.BlockPortAll;
import static com.layoutxml.sabs.Global.BlockedUniqueUrls;

/*
A good chunk of code is generated by mmotti https://github.com/mmotti
and
fusionjack https://github.com/fusionjack/Adhell2
 */

public class ContentBlocker56 implements ContentBlocker {
    private static ContentBlocker56 mInstance = null;
    private final String TAG = ContentBlocker56.class.getCanonicalName();

    @Nullable
    @Inject
    Firewall mFirewall;
    @Inject
    AppDatabase appDatabase;

    private ContentBlocker56() {App.get().getAppComponent().inject(this);}

    public static ContentBlocker56 getInstance() {
        if (mInstance == null) {
            mInstance = getSync();
        }
        return mInstance;
    }

    private static synchronized ContentBlocker56 getSync() {
        if (mInstance == null) {
            mInstance = new ContentBlocker56();
        }
        return mInstance;
    }

    // Initiate the firewall interface
    fwInterface FW = new fwInterface();

    @Override
    public boolean enableBlocker() {

        if (isEnabled()) {
            disableBlocker();
        }
        Log.d(TAG, "Adding: DENY MOBILE DATA");
        List<AppInfo> restrictedApps = appDatabase.applicationInfoDao().getMobileRestrictedApps();
        if (restrictedApps.size() > 0) {
            // Define DENY rules for mobile data
            FirewallRule[] mobileRules = new FirewallRule[restrictedApps.size()];
            for (int i = 0; i < restrictedApps.size(); i++) {
                mobileRules[i] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
                mobileRules[i].setNetworkInterface(Firewall.NetworkInterface.MOBILE_DATA_ONLY);
                mobileRules[i].setApplication(new AppIdentity(restrictedApps.get(i).packageName, null));
            }

            // Send rules to the firewall
            FirewallResponse[] response = mFirewall.addRules(mobileRules);
            if (FirewallResponse.Result.SUCCESS == response[0].getResult()) {
                Log.i(TAG, "Mobile data rules have been added: " + response[0].getMessage());
            } else {
                Log.i(TAG, "Failed to add mobile data rules: " + response[0].getMessage());
            }
        }
        Log.i(TAG, "Restricted apps size: " + restrictedApps.size());

        // Set the AppIdentity (all packages)
        AppIdentity appIdentity = new AppIdentity("*", null);

        /*
        ==============================================================
        Block Port 53
        ==============================================================
        */

        // If the user would like to block Port 53
        if(BlockPort53)
        {
            // Create a FirewallRule to hold our rules
            FirewallRule[] portRules = new FirewallRule[2];

            // IPv4
            portRules[0] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
            portRules[0].setIpAddress("*");
            portRules[0].setPortNumber("53");
            // IPv6
            portRules[1] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV6);
            portRules[1].setIpAddress("*");
            portRules[1].setPortNumber("53");

            // If the user would like to block Port 53 for ALL packages
            if(BlockPortAll)
            {
                Log.d(TAG, "Adding: Deny * Port 53");

                // If unable to add the rules to the firewall
                if(!FW.addFirewallRules(portRules))
                {
                    // return false (break operation)
                    return false;
                }
            }
            else
            {
                // Create an array with known Chrome packages
                List<String> Port53Apps = new ArrayList<>(Arrays.asList("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary"));

                // For each Chrome Package
                for (String app : Port53Apps) {
                    // IPv4
                    portRules[0].setApplication(new AppIdentity(app, null));
                    // IPv6
                    portRules[1].setApplication(new AppIdentity(app, null));

                    Log.i(TAG, "Adding IPV4/6 rule for: " + app);

                    // If unable to add the rules to the firewall
                    if(!FW.addFirewallRules(portRules))
                    {
                        //return false (break the operation)
                        return false;
                    }
                }
            }
        }

        /*
        ==============================================================
        Whitelist
        ==============================================================
        */

        // Create a new whitelist List
        List<WhiteUrl> whiteUrls = appDatabase.whiteUrlDao().getAll2();

        // If there are whitelist values specified
        if(!whiteUrls.isEmpty()) {

            // Create a new whitelist array to hold our whitelisted sites
            List<String> whiteUrlsString = new ArrayList<>();

            // For each whitelisted site in the database
            for (WhiteUrl whiteUrl : whiteUrls) {

                if (BlockUrlPatternsMatch.domainValid(whiteUrl.url))
                {

                    // Remove www. www1. etc
                    // Necessary as we do it for the denylist
                    whiteUrl.url = whiteUrl.url.replaceAll("^(www)([0-9]{0,3})?(\\.)", "");

                    // Unblock the same domain with www prefix
                    final String urlReady = "*" + whiteUrl.url;

                    // Add to our array
                    whiteUrlsString.add(urlReady);
                } else if (BlockUrlPatternsMatch.wildcardValid(whiteUrl.url)) {
                    // Add to our array
                    whiteUrlsString.add(whiteUrl.url);
                }
            }

            // Create a new arraylist to hold our whitelisted domains
            List<DomainFilterRule> whiterules = new ArrayList<>();

            // Add our whitelisted domains to the array
            whiterules.add(new DomainFilterRule(appIdentity, new ArrayList<>(), whiteUrlsString));

            Log.i(TAG, "Adding whitelist rules.");

            // If unable to add the rules to the firewall
            if(!FW.addDomainFilterRules(whiterules))
            {
                // return false (break operation)
                return false;
            }
        }

        /*
        ==============================================================
        Denylist
        ==============================================================
        */

        // Create our denyList array
        Set<String> denySet = new HashSet<>();
        // Create a new BlockUrlProvider list and populate it with the selected lists in the DB
        List<BlockUrlProvider> blockUrlProviders = appDatabase.blockUrlProviderDao().getBlockUrlProviderBySelectedFlag(1);

        Log.i(TAG, "Adding denylist rules.");

        // For each block provider
        for (BlockUrlProvider blockUrlProvider : blockUrlProviders) {

            // Get the domains for the given list
            List<BlockUrl> blockUrls = appDatabase.blockUrlDao().getUrlsByProviderId(blockUrlProvider.id);

            // For each domain
            for (BlockUrl blockUrl : blockUrls) {

                // Remove www. www1. etc
                // Necessary as we do it for the whiteUrls, domain could get through the blocker if it doesn't start with www
                blockUrl.url = blockUrl.url.replaceAll("^(www)([0-9]{0,3})?(\\.)", "");

                // If we have a wildcard
                if (blockUrl.url.contains("*")) {
                    // Add it exactly how it is
                    denySet.add(blockUrl.url);
                } else {
                    // Otherwise append a leading * so that the domain with prefix www was also blocked
                    denySet.add("*" + blockUrl.url);
                }
            }
        }

        /*
        ==============================================================
        User defined denyList
        ==============================================================
        */

        //Create a new UserBlockUrl list and populate it with the user defined block list
        List<UserBlockUrl> userBlockUrls = appDatabase.userBlockUrlDao().getAll2();

        //If there are useBlockUrls values specified
        if (!userBlockUrls.isEmpty()) {

            //For each blacklisted domain
            for (UserBlockUrl userBlockUrl : userBlockUrls) {

                if (BlockUrlPatternsMatch.domainValid(userBlockUrl.url))
                {
                    // Remove www. www1. etc
                    // Necessary as we do it for the denylist, whiteUrls, domain could get through the blocker if it doesn't start with www
                    userBlockUrl.url = userBlockUrl.url.replaceAll("^(www)([0-9]{0,3})?(\\.)", "");

                    //Block the same domain with www prefix
                    final String urlReady = "*" + userBlockUrl.url;

                    denySet.add(urlReady);

                } else if (BlockUrlPatternsMatch.wildcardValid(userBlockUrl.url)) {

                    denySet.add(userBlockUrl.url);

                }
            }
        }

        // Add unique domains from hash set
        List<String> denyList = new ArrayList<>(denySet);

        // Get the count of the unique domains
        BlockedUniqueUrls = denyList.size();
        MainActivity.updateBlockCount();

        // Split the denylist into chunks
        List<List<String>> partitionedDenyList = SplitList.partition(denyList, 5000);

        // Get the amount of denylist partitions
        final int partitionCount = partitionedDenyList.size();
        // Set the partition loop iteration
        int partitionNo = 1;

        // For each partitioned list
        for(List<String> partition : partitionedDenyList)
        {
            // Get some useful debug info
            final int thisPartitionSize = partition.size();

            // Create a new 'rules' arraylist
            List<DomainFilterRule> denyrules = new ArrayList<>();

            // Add the partitioned denyList to the rules array
            denyrules.add(new DomainFilterRule(appIdentity, partition, new ArrayList<>()));

            // Try to add rules to the firewall

                // Build a string for debug output
                String partitionDebug = MessageFormat.format("Adding list: {0} of {1} ({2} domains)", partitionNo, partitionCount, thisPartitionSize);
                Log.i(TAG, partitionDebug);

                // If unable to add the rules to the firewall
                if(!FW.addDomainFilterRules(denyrules))
                {
                    // return false (break operation)
                    return false;
                }

            // Increment iteration #
            partitionNo++;
        }

        /*
        ==============================================================
        APP Whitelisting
        ==============================================================
        */

        // Create a new 'rules' arraylist
        List<DomainFilterRule> apprules = new ArrayList<>();
        // Create an array that will represent all packages
        List<String> superAllow = new ArrayList<>();
        superAllow.add("*");
        // Obtain the user whitelisted apps
        List<AppInfo> appInfos = appDatabase.applicationInfoDao().getWhitelistedApps();

        // If there are whitelisted apps
        if(!appInfos.isEmpty()) {
            // For each whitelisted app
            for (AppInfo app : appInfos) {
                apprules.add(new DomainFilterRule(new AppIdentity(app.packageName, null), new ArrayList<>(), superAllow));
            }

            // If unable to add the rules to the firewall
            if(!FW.addDomainFilterRules(apprules))
            {
                // return false (break operation)
                return false;
            }
        }

        // Enable the firewall
        try {
            //FirewallResponse[] response = mFirewall.addDomainFilterRules(rules);
            assert mFirewall != null;
            if (!mFirewall.isFirewallEnabled()) {
                mFirewall.enableFirewall(true);
            }
            if (!mFirewall.isDomainFilterReportEnabled()) {
                mFirewall.enableDomainFilterReport(true);
            }
        } catch (SecurityException ex) {
            return false;
        }

        Log.i(TAG,"SABS enabled successfully.");
        return true;
    }


    @Override
    public boolean disableBlocker() {
        // Set the number of blocked domains to 0
        BlockedUniqueUrls = 0;
        MainActivity.updateBlockCount();

        FirewallResponse[] response;
        try {
            Log.i(TAG, "Clearing SABS firewall rules...");
            assert mFirewall != null;
            response = mFirewall.clearRules(Firewall.FIREWALL_ALL_RULES);
            response = mFirewall.removeDomainFilterRules(DomainFilterRule.CLEAR_ALL);
            if (mFirewall.isFirewallEnabled()) {
                mFirewall.enableFirewall(false);
                Log.i(TAG, "SABS firewall disabled successfully.");

            }
            if (mFirewall.isDomainFilterReportEnabled()) {
                mFirewall.enableDomainFilterReport(false);
                Log.i(TAG, "SABS domain report disabled successfully.");
            }

        } catch (SecurityException ex) {
            return false;
        }

        return true;
    }

    @Override
    public boolean isEnabled() {
        assert mFirewall != null;
        return mFirewall.isFirewallEnabled();
    }

}
