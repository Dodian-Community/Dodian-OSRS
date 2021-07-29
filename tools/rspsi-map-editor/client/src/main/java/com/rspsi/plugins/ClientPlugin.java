package com.rspsi.plugins;

import com.jagex.Client;
import com.jagex.net.ResourceResponse;

public interface ClientPlugin {
	
	void initializePlugin();
	void onGameLoaded(Client client) throws Exception;
	default void onResourceDelivered(ResourceResponse resource) {
		
	}

}
