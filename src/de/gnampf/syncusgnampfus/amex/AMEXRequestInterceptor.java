package de.gnampf.syncusgnampfus.amex;

import de.gnampf.syncusgnampfus.KeyValue;

import java.util.ArrayList;

public class AMEXRequestInterceptor 
{
	public String Body = null;
	public String Url = null;
	public String log = "";
	public ArrayList<KeyValue<String, String>> Header = new ArrayList<KeyValue<String, String>>();
	public AMEXRequestInterceptor() 
	{
	}
}
