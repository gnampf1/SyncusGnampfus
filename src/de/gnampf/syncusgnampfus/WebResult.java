package de.gnampf.syncusgnampfus;

import java.util.List;

import org.htmlunit.util.NameValuePair;
import org.json.JSONObject;

public class WebResult 
{
	private String content;
	private int httpStatus;
	private JSONObject json;
	private List<NameValuePair> responseHeader;

	public WebResult(int httpStatus, String content, JSONObject json, List<NameValuePair> responseHeader)
	{
		this.httpStatus = httpStatus;
		this.content = content;
		this.json = json;
	}

	public String getContent() 
	{
		return content;
	}

	public int getHttpStatus() 
	{
		return httpStatus;
	}

	public JSONObject getJSONObject()
	{
		return json;
	}
	
	public List<NameValuePair> getResponseHeader()
	{
		return responseHeader;
	}
}
