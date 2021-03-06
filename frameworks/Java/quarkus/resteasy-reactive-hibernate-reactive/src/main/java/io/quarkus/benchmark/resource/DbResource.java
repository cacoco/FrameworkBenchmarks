package io.quarkus.benchmark.resource;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.hibernate.FlushMode;
import org.hibernate.reactive.mutiny.Mutiny;

import io.quarkus.benchmark.model.World;
import io.quarkus.benchmark.repository.WorldRepository;
import io.smallrye.mutiny.Uni;

@Produces(MediaType.APPLICATION_JSON)
@Path("/")
public class DbResource {

    @Inject
    WorldRepository worldRepository;

    @GET
    @Path("db")
    public Uni<World> db() {
        return randomWorld();
    }

    @GET
    @Path("queries")
    public Uni<Collection<World>> queries(@QueryParam("queries") String queries) {
        return worldRepository.inSession(session -> randomWorldForRead(session, parseQueryCount(queries)));
    }

    private Uni<Collection<World>> randomWorldForRead(Mutiny.Session session, int count) {
        Set<Integer> ids = new HashSet<>(count);
        int counter = 0;
        while (counter < count) {
            counter += ids.add(Integer.valueOf(randomWorldNumber())) ? 1 : 0;
        }
        return worldRepository.find(session, ids);
    }

    @GET
    @Path("updates")
    public Uni<Collection<World>> updates(@QueryParam("queries") String queries) {
        return worldRepository.inSession(session -> {
            // FIXME: not supported
            //          session.setJdbcBatchSize(worlds.size());
            session.setFlushMode(FlushMode.MANUAL);

            var worlds = randomWorldForRead(session, parseQueryCount(queries));
            return worlds.flatMap(worldsCollection -> {
                worldsCollection.forEach( w -> {
                    //Read the one field, as required by the following rule:
                    // # vi. At least the randomNumber field must be read from the database result set.
                    final int previousRead = w.getRandomNumber();
                    //Update it, but make sure to exclude the current number as Hibernate optimisations would have us "fail"
                    //the verification:
                    w.setRandomNumber(randomWorldNumber(previousRead));
                } );
                
                return worldRepository.update(session, worldsCollection);
            });
        });
    }

    private Uni<World> randomWorld() {
        return worldRepository.find(randomWorldNumber());
    }

    private int randomWorldNumber() {
        return 1 + ThreadLocalRandom.current().nextInt(10000);
    }

    /**
     * Also according to benchmark requirements, except that in this special case
     * of the update test we need to ensure we'll actually generate an update operation:
     * for this we need to generate a random number between 1 to 10000, but different
     * from the current field value.
     * @param previousRead
     * @return
     */
    private int randomWorldNumber(final int previousRead) {
        //conceptually split the random space in those before previousRead,
        //and those after: this approach makes sure to not affect the random characteristics.
        final int trueRandom = ThreadLocalRandom.current().nextInt(9999) + 2;
        if (trueRandom<=previousRead) {
            //all figures equal or before the current field read need to be shifted back by one
            //so to avoid hitting the same number while not affecting the distribution.
            return trueRandom - 1;
        }
        else {
            //Those after are generated by taking the generated value 2...10000 as is.
            return trueRandom;
        }
    }

    private int parseQueryCount(String textValue) {
        if (textValue == null) {
            return 1;
        }
        int parsedValue;
        try {
            parsedValue = Integer.parseInt(textValue);
        } catch (NumberFormatException e) {
            return 1;
        }
        return Math.min(500, Math.max(1, parsedValue));
    }
}