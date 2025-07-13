package de.gnampf.syncusgnampfus.amex;

import de.gnampf.syncusgnampfus.KeyValue;
import website.magyar.mitm.proxy.RequestInterceptor;
import website.magyar.mitm.proxy.http.MitmJavaProxyHttpRequest;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import org.apache.http.Header;

public class AMEXRequestInterceptor implements RequestInterceptor 
{
	public String Body = null;
	public String Url = null;
	public ArrayList<KeyValue<String, String>> Header = new ArrayList<KeyValue<String, String>>();
	    public AMEXRequestInterceptor() {
	    }

	    public void process(final MitmJavaProxyHttpRequest request) {
	    	HttpRequestBase b = request.getMethod();
	        
	    	String path = b.getURI().getPath().toLowerCase(); 
	    	if (path.equals("/myca/logon/emea/action/login") && b.getMethod() == "POST")
	    	{
	    		try 
	    		{
	    			for (Header h : b.getAllHeaders())
	    			{
	    				Header.add(new KeyValue<>(h.getName(), h.getValue()));
	    			}
	    			HttpPost post = (HttpPost)b;
	    			Body = new String(post.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
	    			Url = b.getURI().toString();
	    		}catch (Exception e) { }
	    	}

	    	if (Url != null)
	    	{
	    		try 
	    		{
	    			b.setURI(new URI("http://gibtsnicht/nirgends"));
	    		}
	    		catch (Exception e) {}
	    		b.abort();
	    	}
	    }
}
