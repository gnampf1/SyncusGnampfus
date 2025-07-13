package de.gnampf.syncusgnampfus;

import java.util.List;

import org.htmlunit.util.NameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

public class WebResult 
{
	private String content;
	private int httpStatus;
	private JSONObject json;
	private List<NameValuePair> responseHeader;

	public WebResult(int httpStatus, String content, List<NameValuePair> responseHeader)
	{
		this.httpStatus = httpStatus;
		this.content = content;
		this.responseHeader = responseHeader;
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
		JSONObject json;
		if (content != null && !content.isBlank())
		{
			try 
			{
				return new JSONObject(content);
			}
			catch (Exception e) {}
		}

		return new JSONObject();
	}
	
	public JSONArray getJSONArray()
	{
		JSONArray json;
		if (content != null && !content.isBlank())
		{
			try 
			{
				return new JSONArray(content);
			}
			catch (Exception e) {}
		}

		return new JSONArray();
	}
	
	public List<NameValuePair> getResponseHeader()
	{
		return responseHeader;
	}
}
