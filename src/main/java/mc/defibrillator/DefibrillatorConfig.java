/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator;

import mc.microconfig.Comment;
import mc.microconfig.ConfigData;

public class DefibrillatorConfig implements ConfigData {
    @Comment("If Defibrillator should re-throw async errors to force the main server to crash\nNote that this can lead to strange de-syncs or even worse crashes if disabled")
    public boolean rethrowAsyncErrors = true;
    
    @Comment("If the \"Number\" option should be shown instead of the specific tag options")
    public boolean collapseNumberOptions = true;
    
    @Comment("Message shown to users who try to join while their data is being edited, use %editor% for the player name of the editor")
    public String failedConnectMessage = "Your player data is being edited! Try again later.";
    
    public CommandConfigs commands = new CommandConfigs();
    
    public CrashConfig errorManagement = new CrashConfig();
    
    public static class CommandConfigs implements ConfigData {
        @Comment("Permission level required to access /defib")
        public int minimumRequiredLevel = 2;
        
        @Comment("Permission level required to view data")
        public int viewRequiredLevel = 2;
    
        @Comment("Permission level required to edit data")
        public int editRequiredLevel = 2;
        
        @Comment("If the debug subset of commands is enabled")
        public boolean enableDebugCommands = true;
    }
    
    public static class CrashConfig implements ConfigData {
        @Comment("If entities that cause a crash should be prevented from ticking")
        public boolean autoPauseCrashingEntities = false;
    
        @Comment("If ticking block entities that cause a crash should be prevented from ticking")
        public boolean autoPauseCrashingBlocks = false;
        
        @Comment("How long until frozen objects are unfrozen automatically in attempted ticks, -1 to disable auto retry")
        public int retryDelay = 200;
    }
}
