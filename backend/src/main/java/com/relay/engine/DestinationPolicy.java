package com.relay.engine;

import java.net.URI;
import java.util.*;

/** Exact deployment-controlled origins. Redirects and workflow-supplied credentials are forbidden. */
public final class DestinationPolicy {
    private final Set<String> origins;
    public DestinationPolicy(String configured) {
        var values=new HashSet<String>();
        for(String text:configured.split(",",-1)) {
            URI uri=parse(text.strip());
            if(!(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) || uri.getRawQuery()!=null)throw new IllegalArgumentException("Allowed destinations must be origins");
            values.add(origin(uri));
        }
        origins=Set.copyOf(values);
    }
    public URI check(String url) {
        URI uri=parse(url);
        if(!origins.contains(origin(uri)))throw new NodeFailure("destination_denied");
        return uri;
    }
    static URI parse(String text) {
        try {
            URI uri=URI.create(text);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getRawFragment()!=null || uri.getPort()==0 || uri.getPort()>65535)throw new IllegalArgumentException();
            return uri;
        } catch(IllegalArgumentException ex){throw new NodeFailure("invalid_destination");}
    }
    static String origin(URI uri){return uri.getScheme()+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+(uri.getPort()<0?(uri.getScheme().equals("https")?443:80):uri.getPort());}
}
