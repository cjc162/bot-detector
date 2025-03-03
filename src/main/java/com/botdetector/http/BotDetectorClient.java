/*
 * Copyright (c) 2021, Ferrariic, Seltzer Bro, Cyborger1
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.botdetector.http;

import com.botdetector.model.PlayerSighting;
import com.botdetector.model.PlayerStats;
import com.botdetector.model.Prediction;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class BotDetectorClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String API_VERSION_FALLBACK_WORD = "latest";
	private static final HttpUrl BASE_HTTP_URL = HttpUrl.parse(
		System.getProperty("BotDetectorAPIPath", "https://www.osrsbotdetector.com/api"));

	@Getter
	@AllArgsConstructor
	private enum ApiPath
	{
		DETECTION("plugin/detect/"),
		PLAYER_STATS("stats/contributions/"),
		PREDICTION("site/prediction/"),
		FEEDBACK("plugin/predictionfeedback/"),
		VERIFY_DISCORD("site/discord_user/")
		;

		final String path;
	}

	public static OkHttpClient okHttpClient = RuneLiteAPI.CLIENT.newBuilder()
		.pingInterval(0, TimeUnit.SECONDS)
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.build();

	@Inject
	private GsonBuilder gsonBuilder;

	@Getter
	@Setter
	private String pluginVersion;

	private HttpUrl getUrl(ApiPath path)
	{
		String version = (pluginVersion != null && !pluginVersion.isEmpty()) ?
			pluginVersion : API_VERSION_FALLBACK_WORD;

		return BASE_HTTP_URL.newBuilder()
			.addPathSegment(version)
			.addPathSegments(path.getPath())
			.build();
	}

	public CompletableFuture<Boolean> sendSighting(PlayerSighting sighting, String reporter, boolean manual)
	{
		return sendSightings(ImmutableList.of(sighting), reporter, manual);
	}

	public CompletableFuture<Boolean> sendSightings(Collection<PlayerSighting> sightings, String reporter, boolean manual)
	{
		List<PlayerSightingWrapper> wrappedList = sightings.stream()
			.map(p -> new PlayerSightingWrapper(reporter, p)).collect(Collectors.toList());

		Gson gson = gsonBuilder
			.registerTypeAdapter(PlayerSightingWrapper.class, new PlayerSightingWrapperSerializer())
			.registerTypeAdapter(Boolean.class, new BooleanToZeroOneSerializer())
			.registerTypeAdapter(Instant.class, new InstantSecondsConverter())
			.create();

		Request request = new Request.Builder()
			.url(getUrl(ApiPath.DETECTION).newBuilder()
				.addPathSegment(String.valueOf(manual ? 1 : 0))
				.build())
			.post(RequestBody.create(JSON, gson.toJson(wrappedList)))
			.build();

		CompletableFuture<Boolean> future = new CompletableFuture<>();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Error sending player sighting data", e);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful())
					{
						throw getIOException(response);
					}

					future.complete(true);
				}
				catch (IOException e)
				{
					log.warn("Error sending player sighting data", e);
					future.completeExceptionally(e);
				}
				finally
				{
					response.close();
				}
			}
		});

		return future;
	}

	public CompletableFuture<Boolean> verifyDiscord(String token, String nameToVerify, String code)
	{
		Gson gson = gsonBuilder.create();

		Request request = new Request.Builder()
			.url(getUrl(ApiPath.VERIFY_DISCORD).newBuilder()
				.addPathSegment(token)
				.build())
			.post(RequestBody.create(JSON, gson.toJson(new DiscordVerification(nameToVerify, code))))
			.build();

		CompletableFuture<Boolean> future = new CompletableFuture<>();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Error verifying discord user", e);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					// TODO: Differenciate between bad token and failed auth (return false)
					if (!response.isSuccessful())
					{
						if (response.code() == 401)
						{
							throw new UnauthorizedTokenException("Invalid or unauthorized token for operation");
						}
						else
						{
							throw getIOException(response);
						}
					}

					future.complete(true);
				}
				catch (UnauthorizedTokenException | IOException e)
				{
					log.warn("Error verifying discord user", e);
					future.completeExceptionally(e);
				}
				finally
				{
					response.close();
				}
			}
		});

		return future;
	}

	public CompletableFuture<Boolean> sendFeedback(Prediction pred, String reporterName, boolean feedback)
	{
		Gson gson = gsonBuilder.create();

		Request request = new Request.Builder()
			.url(getUrl(ApiPath.FEEDBACK))
			.post(RequestBody.create(JSON, gson.toJson(new PredictionFeedback(
				reporterName,
				feedback ? 1 : -1,
				pred.getPredictionLabel(),
				pred.getConfidence(),
				pred.getPlayerId()
			)))).build();

		CompletableFuture<Boolean> future = new CompletableFuture<>();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Error sending prediction feedback", e);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful())
					{
						throw getIOException(response);
					}

					future.complete(true);
				}
				catch (IOException e)
				{
					log.warn("Error sending prediction feedback", e);
					future.completeExceptionally(e);
				}
				finally
				{
					response.close();
				}
			}
		});

		return future;
	}

	public CompletableFuture<Prediction> requestPrediction(String playerName)
	{
		Gson gson = gsonBuilder.create();

		Request request = new Request.Builder()
			.url(getUrl(ApiPath.PREDICTION).newBuilder()
				.addPathSegment(playerName)
				.build())
			.build();

		CompletableFuture<Prediction> future = new CompletableFuture<>();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Error obtaining player prediction data", e);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					future.complete(processResponse(gson, response, Prediction.class));
				}
				catch (IOException e)
				{
					log.warn("Error obtaining player prediction data", e);
					future.completeExceptionally(e);
				}
				finally
				{
					response.close();
				}
			}
		});

		return future;
	}

	public CompletableFuture<PlayerStats> requestPlayerStats(String playerName)
	{
		Gson gson = gsonBuilder.create();

		Request request = new Request.Builder()
			.url(getUrl(ApiPath.PLAYER_STATS).newBuilder()
				.addPathSegment(playerName)
				.build())
			.build();

		CompletableFuture<PlayerStats> future = new CompletableFuture<>();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Error obtaining player stats data", e);
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					future.complete(processResponse(gson, response, PlayerStats.class));
				}
				catch (IOException e)
				{
					log.warn("Error obtaining player stats data", e);
					future.completeExceptionally(e);
				}
				finally
				{
					response.close();
				}
			}
		});

		return future;
	}

	private <T> T processResponse(Gson gson, Response response, Class<T> classOfT) throws IOException
	{
		if (!response.isSuccessful())
		{
			if (response.code() == 404)
			{
				return null;
			}

			throw getIOException(response);
		}

		try
		{
			return gson.fromJson(response.body().string(), classOfT);
		}
		catch (IOException | JsonSyntaxException ex)
		{
			throw new IOException("Error parsing API response body", ex);
		}
	}

	private IOException getIOException(Response response)
	{
		int code = response.code();
		if (code >= 400 && code < 500)
		{
			try
			{
				Map<String, String> map = gsonBuilder.create().fromJson(response.body().string(),
					new TypeToken<Map<String, String>>()
					{
					}.getType());
				return new IOException(map.getOrDefault("error", "Unknown " + code + " error from API"));
			}
			catch (IOException | JsonSyntaxException ex)
			{
				return new IOException("Error " + code + " with no error info", ex);
			}
		}

		return new IOException("Error " + code + " from API");
	}

	@Value
	private static class PlayerSightingWrapper
	{
		String reporter;
		@SerializedName("sighting_data")
		PlayerSighting sightingData;
	}

	@Value
	private static class DiscordVerification
	{
		@SerializedName("player_name")
		String nameToVerify;
		String code;
	}

	@Value
	private static class PredictionFeedback
	{
		@SerializedName("player_name")
		String playerName;
		int vote;
		@SerializedName("prediction")
		String predictionLabel;
		@SerializedName("confidence")
		double predictionConfidence;
		@SerializedName("subject_id")
		int targetId;
	}

	private static class PlayerSightingWrapperSerializer implements JsonSerializer<PlayerSightingWrapper>
	{
		@Override
		public JsonElement serialize(PlayerSightingWrapper src, Type typeOfSrc, JsonSerializationContext context)
		{
			JsonElement json = context.serialize(src.getSightingData());
			json.getAsJsonObject().addProperty("reporter", src.getReporter());
			return json;
		}
	}

	private static class BooleanToZeroOneSerializer implements JsonSerializer<Boolean>
	{
		@Override
		public JsonElement serialize(Boolean src, Type typeOfSrc, JsonSerializationContext context)
		{
			return context.serialize(src ? 1 : 0);
		}
	}

	private static class InstantSecondsConverter implements JsonSerializer<Instant>, JsonDeserializer<Instant>
	{
		@Override
		public JsonElement serialize(Instant src, Type srcType, JsonSerializationContext context)
		{
			return new JsonPrimitive(src.getEpochSecond());
		}

		@Override
		public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext context)
			throws JsonParseException
		{
			return Instant.ofEpochSecond(json.getAsLong());
		}
	}
}
