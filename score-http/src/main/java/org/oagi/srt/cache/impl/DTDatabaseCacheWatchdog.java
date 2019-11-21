package org.oagi.srt.cache.impl;

import org.oagi.srt.cache.DatabaseCacheWatchdog;
import org.oagi.srt.data.DT;
import org.oagi.srt.repository.DTRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DTDatabaseCacheWatchdog extends DatabaseCacheWatchdog<DT> {

    public DTDatabaseCacheWatchdog(@Autowired DTRepository delegate) {
        super("dt", DT.class, delegate);
    }

}